package scala

import language.experimental.macros
import language.higherKinds

import scala.reflect.macros.{TypecheckException, Context}

package object workflow extends FunctorInstances with SemiIdiomInstances with IdiomInstances with MonadInstances {
  def context[F[_]](code: _): _ = macro contextImpl
  def contextImpl(c: Context)(code: c.Tree): c.Tree = {
    import c.universe._

    val Apply(TypeApply(_, List(typeTree)), _) = c.macroApplication

    c.macroApplication.updateAttachment(contextFromType(c)(typeTree))

    code
  }

  object context {
    def apply(workflow: Any)(code: _): _ = macro contextImpl
    def contextImpl(c: Context)(workflow: c.Expr[Any])(code: c.Tree): c.Tree = {
      import c.universe._

      val Expr(instance) = workflow

      c.macroApplication.updateAttachment(contextFromTerm(c)(instance))

      code
    }
  }

  def workflow[F[_]](code: _): _ = macro workflowImpl
  def workflowImpl(c: Context)(code: c.Tree): c.Tree = {
    import c.universe._

    val Apply(TypeApply(_, List(typeTree)), _) = c.macroApplication

    val workflowContext = contextFromType(c)(typeTree)

    rewrite(c)(code, workflowContext).asInstanceOf[Tree]
  }

  object workflow {
    def apply(workflow: Any)(code: _): _ = macro workflowImpl
    def workflowImpl(c: Context)(workflow: c.Expr[Any])(code: c.Tree): c.Tree = {
      import c.universe._

      val Expr(instance) = workflow

      val workflowContext = contextFromTerm(c)(instance)

      rewrite(c)(code, workflowContext).asInstanceOf[Tree]
    }
  }

  def $[F[_]](code: _): _ = macro $impl
  def $impl(c: Context)(code: c.Tree): c.Tree = {
    import c.universe._

    val Apply(TypeApply(_, List(typeTree: TypeTree)), _) = c.macroApplication

    val workflowContext = if (typeTree.original != null)
                            contextFromType(c)(typeTree)
                          else
                            contextFromEnclosingIdiom(c)

    rewrite(c)(code, workflowContext).asInstanceOf[Tree]
  }

  private def contextFromType(c: Context)(typeTree: c.Tree) = {
    import c.universe._

    val tpe = typeTree.tpe

    val typeRef = TypeRef(NoPrefix, typeOf[Workflow[Any]].typeSymbol, List(tpe))
    val instance = c.inferImplicitValue(typeRef)

    if (instance == EmptyTree)
      c.abort(typeTree.pos, s"Unable to find $typeRef instance in implicit scope")

    WorkflowContext(tpe, instance)
  }

  private def contextFromTerm(c: Context)(instance: c.Tree): WorkflowContext = {
    import c.universe._

    val workflowSymbol = instance.tpe.baseClasses find (_.fullName == "scala.workflow.Workflow") getOrElse {
      c.abort(instance.pos, "Not a workflow instance")
    }

    val TypeRef(_, _, List(tpe)) = instance.tpe.baseType(workflowSymbol)

    WorkflowContext(tpe, instance)
  }

  private def contextFromEnclosingIdiom(c: Context) = {
    val workflowContext = for {
      context ← c.openMacros.view
      attachments = context.macroApplication.attachments
      workflowContext ← attachments.get[WorkflowContext]
    } yield workflowContext

    workflowContext.headOption getOrElse {
      c.abort(c.enclosingPosition, "Workflow brackets outside of `context' block")
    }
  }

  private def rewrite(c: Context)(code: c.Tree, workflowContext: WorkflowContext): c.Tree = {
    import c.universe._

    val WorkflowContext(workflow: Type, instance: Tree) = workflowContext

    def resolveLiftedType(tpe: Type): Option[Type] =
      tpe.baseType(workflow.typeSymbol) match {
        case baseType @ TypeRef(_, _, typeArgs) ⇒
          workflow match {
            case PolyType(List(wildcard), typeRef: TypeRef) ⇒
              // When workflow is passed as type lambda, we need to take the type
              // from wildcard position, so we zip through both typerefs to seek for a substitution
              def findSubstitution(wildcardedType: Type, concreteType: Type): Option[Type] = {
                if (wildcardedType.typeSymbol == wildcard)
                  Some(concreteType)
                else
                  (wildcardedType, concreteType) match {
                    case (wctpe: TypeRef, ctpe: TypeRef) ⇒
                      wctpe.args zip ctpe.args find {
                        case (wct, at) ⇒ !(wct =:= at)
                      } flatMap {
                        case (wct, at) ⇒ findSubstitution(wct, at)
                      }
                    case _ ⇒ None
                  }
              }
              findSubstitution(typeRef, baseType)
            case _ ⇒
              // This only works for type constructor of one argument
              // TODO: provide implementation for n-arity type constructors
              typeArgs.headOption
          }
        case _ ⇒ None
      }

    case class Bind(name: TermName, tpt: TypeTree, value: Tree) {
      def isUsedIn(frame: Frame) = frame exists ((_: Bind).value exists (_ equalsStructure q"$name"))
    }
    type Frame = List[Bind]
    class Scope(val materialized: Frame, val frames: List[Frame]) {
      def materialize(bind: Bind) = new Scope(materialized :+ bind, frames)
      def :+ (bind: Bind) = new Scope(materialized, (bind :: frames.head) :: frames.tail)
      def enter = new Scope(materialized, Nil :: frames)
      def leave = new Scope(materialized, frames.tail)
      def local = materialized ++ frames.flatten
    }
    object Scope {
      val empty = new Scope(Nil, List(Nil))
      def merge(scopes: List[Scope]) = {
        val materialized = scopes map (_.materialized)
        val frames = scopes map (_.frames)
        new Scope(materialized.flatten.distinct, frames.flatten.distinct)
      }
    }

    def typeCheck(tree: Tree, scope: Scope): Option[Tree] = {
      def inScope(tree: Tree) = scope.local.foldLeft(tree) {
        case (expr, bind) ⇒ q"{ val ${bind.name}: ${bind.tpt} = ???; $expr }"
      }
      try {
        Some(c.typeCheck(inScope(tree.duplicate)))
      } catch {
        case e: TypecheckException if e.msg contains "follow this method with `_'" ⇒ Some(EmptyTree)
        case e: TypecheckException if e.msg contains "missing arguments for constructor" ⇒
          try {
            Some(c.typeCheck(inScope(q"(${tree.duplicate})(_)")))
          } catch {
            case e: TypecheckException if !(e.msg contains "too many arguments for constructor") ⇒ Some(EmptyTree)
            case e: Exception ⇒ None
          }
        case e: TypecheckException if e.msg contains "ambiguous reference" ⇒ Some(EmptyTree)
        case e: TypecheckException if (e.msg contains "package") && (e.msg contains "is not a value") ⇒ Some(EmptyTree)
        case e: Exception ⇒ None
      }
    }

    def rewrite(scope: Scope): Tree ⇒ (Scope, Tree) = {
      case Apply(fun, args) ⇒
        val (funscope,   newfun)  = rewrite(scope)(fun)
        val (argsscopes, newargs) = args.map(rewrite(funscope)).unzip
        extractBinds(Scope.merge(argsscopes), q"$newfun(..$newargs)")

      case Select(value, method) ⇒
        val (newscope, newvalue) = rewrite(scope)(value)
        extractBinds(newscope, q"$newvalue.$method")

      case ValDef(NoMods, name, tpt, expr) ⇒
        val (newscope, newexpr) = rewrite(scope)(expr)
        val tpe = typeCheck(newexpr, newscope).get.tpe
        val newbind = Bind(name, TypeTree(tpe), newexpr)
        (newscope :+ newbind, q"val $name: $tpt = $newexpr")

      case Block(stat :: stats, expr) ⇒
        val (statscope, newstat)  = rewrite(scope.enter)(stat)
        val (newscope,  newblock) = rewrite(statscope.enter)(q"{ ..$stats; $expr }")
        (newscope.leave, q"{ $newstat; $newblock }")

      case Block(Nil, expr) ⇒ rewrite(scope)(expr)

      case expr @ (_ : Literal | _ : Ident | _ : New) ⇒ extractBinds(scope, expr)

      case expr ⇒
        c.abort(expr.pos, "Unsupported expression " + showRaw(expr))
    }

    def extractBinds(scope: Scope, expr: Tree) =
      typeCheck(expr, scope) match {
        case Some(tpt) ⇒
          resolveLiftedType(tpt.tpe) match {
            case Some(tpe) ⇒
              val name = TermName(c.freshName("arg$"))
              val bind = Bind(name, TypeTree(tpe), expr)
              (scope materialize bind, q"$name")

            case None ⇒ (scope, expr)
          }
        case None ⇒ rewrite(scope)(expr)
      }

    def lambda(bind: Bind): Tree ⇒ Tree = {
      expr ⇒ q"(${bind.name}: ${bind.tpt}) ⇒ $expr"
    }

    val interfaces = instance.tpe.baseClasses map (_.fullName)
    def assertImplements(interface: String) {
      if (!interfaces.contains(interface))
        c.abort(c.enclosingPosition, s"Enclosing workflow for type $workflow does not implement $interface")
    }

    def point: Tree ⇒ Tree = {
      assertImplements("scala.workflow.Pointing")
      expr ⇒ q"$instance.point($expr)"
    }

    def map(bind: Bind): Tree ⇒ Tree = {
      assertImplements("scala.workflow.Mapping")
      expr ⇒ q"$instance.map($expr)(${bind.value})"
    }

    def app(bind: Bind): Tree ⇒ Tree = {
      assertImplements("scala.workflow.Applying")
      expr ⇒ q"$instance.app($expr)(${bind.value})"
    }

    def >>=(bind: Bind): Tree ⇒ Tree = {
      assertImplements("scala.workflow.Binding")
      expr ⇒ q"$instance.bind($expr)(${bind.value})"
    }

    def apply: Frame ⇒ Tree ⇒ Tree = {
      case Nil ⇒ point
      case bind :: Nil ⇒ map(bind) compose lambda(bind)
      case bind :: binds ⇒
        if (bind isUsedIn binds)
          >>=(bind) compose lambda(bind) compose apply(binds)
        else
          app(bind) compose apply(binds) compose lambda(bind)
    }

    val (scope, expr) = rewrite(Scope.empty)(code)
    apply(scope.materialized)(expr)
  }
}

package workflow {
  private[workflow] case class WorkflowContext(tpe: Any, instance: Any)
}