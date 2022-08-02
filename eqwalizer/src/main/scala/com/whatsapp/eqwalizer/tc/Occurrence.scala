/* Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved.
 *
 * This source code is licensed under the Apache 2.0 license found in
 * the LICENSE file in the root directory of this source tree.
 */

package com.whatsapp.eqwalizer.tc

import com.whatsapp.eqwalizer.ast.Guards._
import com.whatsapp.eqwalizer.ast.Exprs._
import com.whatsapp.eqwalizer.ast.{Id, Vars}
import com.whatsapp.eqwalizer.ast.Pats._
import com.whatsapp.eqwalizer.ast.Types._

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

object Occurrence {
  sealed trait Prop
  case object Unknown extends Prop
  case class Pos(obj: Obj, t: Type) extends Prop
  case class Neg(obj: Obj, t: Type) extends Prop
  case class And(props: List[Prop]) extends Prop
  case class Or(props: List[Prop]) extends Prop

  sealed trait Obj
  case class VarObj(v: String) extends Obj
  case class FieldObj(field: Field, obj: Obj) extends Obj

  sealed trait Field
  case class TupleField(index: Int, arity: Int) extends Field
  case class RecordField(field: String, recName: String) extends Field

  type PropEnv = List[Prop]
  type AMap = Map[String, Obj]

  implicit class MaybeOps(maybe: Option[Boolean]) {
    @inline
    def isTrue: Boolean = maybe.contains(true)
    @inline
    def isFalse: Boolean = maybe.contains(false)
  }

  private sealed trait Polarity
  private case object + extends Polarity
  private case object - extends Polarity
  type Path = List[Field]
  type PosNeg = (List[Pos], List[Neg])

  val unary_predicates: Map[String, Type] =
    Map(
      "is_atom" -> AtomType,
      "is_binary" -> BinaryType,
      "is_bitstring" -> BinaryType,
      "is_boolean" -> UnionType(Set(falseType, trueType)),
      "is_float" -> floatType,
      "is_function" -> AnyFunType,
      "is_integer" -> NumberType,
      "is_list" -> ListType(AnyType),
      "is_number" -> NumberType,
      "is_pid" -> PidType,
      "is_port" -> PortType,
      "is_reference" -> ReferenceType,
      "is_map" -> DictMap(AnyType, AnyType),
      "is_tuple" -> AnyTupleType,
    )

  sealed trait ValueKind
  case object AtomKind extends ValueKind
  case object BinaryKind extends ValueKind
  case object FunKind extends ValueKind
  case object ListKind extends ValueKind
  case object MapKind extends ValueKind
  case object NumberKind extends ValueKind
  case object PidKind extends ValueKind
  case object PortKind extends ValueKind
  case object ReferenceKind extends ValueKind
  case object TupleKind extends ValueKind

  def kind(t: Type): Option[ValueKind] = t match {
    case AtomLitType(_) | AtomType =>
      Some(AtomKind)
    case BinaryType =>
      Some(BinaryKind)
    case AnyFunType | FunType(_, _, _) =>
      Some(FunKind)
    case NilType | ListType(_) =>
      Some(ListKind)
    case DictMap(_, _) | ShapeMap(_) =>
      Some(MapKind)
    case NumberType =>
      Some(NumberKind)
    case PidType =>
      Some(PidKind)
    case PortType =>
      Some(PortKind)
    case ReferenceType =>
      Some(ReferenceKind)
    case AnyTupleType | TupleType(_) | RecordType(_) | RefinedRecordType(_, _) =>
      Some(TupleKind)
    case _ =>
      None
  }
}

// The main logic of occurrence typing.
final class Occurrence(pipelineContext: PipelineContext) {
  import Occurrence._
  private lazy val module = pipelineContext.module
  private lazy val subtype = pipelineContext.subtype
  private lazy val util = pipelineContext.util
  private lazy val vars = pipelineContext.vars
  private type Name = String
  private var gen = 0
  private def genVar(): String = {
    gen += 1
    s"$$$gen"
  }

  def eqwater(clauses: List[Clause]): Boolean = {
    val emptyPatterns = clauses.forall(_.pats.isEmpty)
    val linearClauses = (pipelineContext.unlimitedRefinement || clauses.size < 7) && clauses.forall(linearVars)
    pipelineContext.eqwater && (emptyPatterns || linearClauses)
  }

  private def linearVars(clause: Clause): Boolean = {
    val varsL = vars.clausePatVarsL(clause)
    varsL.toSet.size == varsL.size
  }

  // These are specialized methods to upgrade environments/context
  // by occurrence typing
  def ifEnvs(i: If, env: Env): List[Env] = {
    var propsAcc = List.empty[Prop]
    val clauseEnvs = ListBuffer.empty[Env]
    for (clause <- i.clauses) {
      val aMap = Map.empty[Name, Obj]
      val (testPos, testNeg) = guardsProps(clause.guards, aMap)
      val clauseProps = propsAcc ++ testPos
      val clauseEnv = batchSelect(env, clauseProps, aMap)
      clauseEnvs.addOne(clauseEnv)
      propsAcc = testNeg match {
        case None =>
          propsAcc
        case Some(neg) =>
          propsAcc :+ neg
      }
    }
    clauseEnvs.toList
  }

  def caseEnvs(c: Case, selType: Type, env: Env): List[Env] = {
    val (env1, x) = c.expr match {
      case Var(n) =>
        (env, n)
      // important for thrift - see D31025723
      case Match(PatVar(n), _) =>
        (env, n)
      case _ =>
        val v = genVar()
        (env + (v -> selType), v)
    }
    var propsAcc = List.empty[Prop]
    val clauseEnvs = ListBuffer.empty[Env]
    for (clause <- c.clauses) {
      val pat = clause.pats.head
      val (patPos, patNeg) = patProps(x, Nil, pat, env).unzip
      val aMap = aliases(x, Nil, pat, env).toMap
      val (testPos, testNeg) = guardsProps(clause.guards, aMap)
      val clauseProps = propsAcc ++ patPos.toList ++ testPos
      val clauseEnv = batchSelect(env1, clauseProps, aMap)
      clauseEnvs.addOne(clauseEnv)
      propsAcc = {
        val allNeg = patNeg.toList ++ testNeg
        allNeg match {
          case Nil =>
            propsAcc
          case h :: Nil =>
            propsAcc :+ h
          case zz =>
            propsAcc :+ Or(zz)
        }
      }
    }
    clauseEnvs.toList
  }

  def clausesEnvs(clauses: List[Clause], argTys: List[Type], env: Env): List[Env] = {
    var propsAcc = List.empty[Prop]
    val clauseEnvs = ListBuffer.empty[Env]

    val vars = argTys.map(_ => genVar())
    val env1 = env ++ vars.zip(argTys).toMap

    for (clause <- clauses) {
      val pats = clause.pats
      val allPos = ListBuffer.empty[Prop]
      val allNeg = ListBuffer.empty[Prop]
      var aMap: AMap = Map.empty
      for ((x, pat) <- vars.zip(pats)) {
        val (patPos, patNeg) = patProps(x, Nil, pat, env).unzip
        patPos.foreach(allPos.addOne)
        patNeg.foreach(allNeg.addOne)
        aMap = aMap ++ aliases(x, Nil, pat, env).toMap
      }
      val (testPos, testNeg) = guardsProps(clause.guards, aMap)
      val clauseProps = propsAcc ++ allPos ++ testPos
      val clauseEnv = batchSelect(env1, clauseProps, aMap)
      clauseEnvs.addOne(clauseEnv)
      propsAcc = {
        val allNeg1 = allNeg.toList ++ testNeg
        allNeg1 match {
          case Nil =>
            propsAcc
          case h :: Nil =>
            propsAcc :+ h
          case zz =>
            propsAcc :+ Or(zz)
        }
      }
    }
    clauseEnvs.toList
  }

  private def aliases(x: String, path: Path, pat: Pat, env: Env): List[(Name, Obj)] =
    pat match {
      case PatVar(v) if !env.contains(v) =>
        val obj = mkObj(x, path)
        List(v -> obj)
      case PatTuple(elems) =>
        val arity = elems.size
        elems.zipWithIndex.flatMap { case (elem, i) =>
          val pathI = path ++ List(TupleField(i, arity))
          aliases(x, pathI, elem, env)
        }
      case PatRecord(recName, fields, gen) =>
        val fieldsAliases =
          fields.flatMap(field => aliases(x, path ++ List(RecordField(field.name, recName)), field.pat, env))
        gen match {
          case None => fieldsAliases
          case Some(genPat) =>
            val recDecl = util.getRecord(module, recName)
            recDecl match {
              case None => fieldsAliases
              case Some(recDecl) =>
                val genAliases =
                  recDecl.fields
                    .filter(fDecl => !fields.exists(f => f.name == fDecl._1))
                    .flatMap(fDecl => aliases(x, path ++ List(RecordField(fDecl._1, recName)), genPat, env))
                    .toList
                genAliases ++ fieldsAliases
            }
        }
      case PatMatch(pat1, pat2) =>
        aliases(x, path, pat1, env) ++ aliases(x, path, pat2, env)
      case _ =>
        Nil
    }

  private def guardsProps(guards: List[Guard], aMap: Map[Name, Obj]): (Option[Prop], Option[Prop]) =
    // the same as connecting via OR
    if (guards.isEmpty)
      (None, None)
    else {
      val (pos, neg) = guards.map(guardProp(_, aMap)).unzip
      (Some(Or(pos)), Some(And(neg)))
    }

  private def guardProp(guard: Guard, aMap: Map[Name, Obj]): (Prop, Prop) = {
    // the same as connecting via AND
    val (pos, neg) = guard.tests.map(testProps(_, aMap)).unzip
    (And(pos), Or(neg))
  }

  private def testProps(test: Test, aMap: Map[Name, Obj]): (Prop, Prop) = {
    test match {
      case TestCall(Id(pred, 1), List(TestVar(v))) if unary_predicates.isDefinedAt(pred) =>
        val obj = aMap.getOrElse(v, VarObj(v))
        val tp = unary_predicates(pred)
        (Pos(obj, tp), Neg(obj, tp))
      case TestCall(Id("is_function", 2), List(TestVar(v), TestNumber(Some(arity)))) =>
        val obj = aMap.getOrElse(v, VarObj(v))
        val tp = FunType(Nil, List.fill(arity.intValue)(AnyType), AnyType)
        (Pos(obj, tp), Neg(obj, tp))
      case TestCall(Id("is_record", 2 | 3), TestVar(v) :: TestAtom(recName) :: _) =>
        val obj = aMap.getOrElse(v, VarObj(v))
        val tp = RecordType(recName)(module)
        (Pos(obj, tp), Neg(obj, tp))
      case TestUnOp("not", test) =>
        val (p1, p2) = testProps(test, aMap)
        (negateGuardProp(p1), negateGuardProp(p2))
      case TestBinOp("and" | "andalso", test1, test2) =>
        val (pos1, neg1) = testProps(test1, aMap)
        val (pos2, neg2) = testProps(test2, aMap)
        (And(List(pos1, pos2)), Or(List(neg1, neg2)))
      case TestBinOp("or" | "orelse", test1, test2) =>
        val (pos1, neg1) = testProps(test1, aMap)
        val (pos2, neg2) = testProps(test2, aMap)
        (Or(List(pos1, pos2)), And(List(neg1, neg2)))
      case TestBinOp("==" | "=:=", TestVar(v), TestAtom(s)) =>
        val obj = aMap.getOrElse(v, VarObj(v))
        val pos = Pos(obj, AtomLitType(s))
        val neg = Neg(obj, AtomLitType(s))
        (pos, neg)
      case TestBinOp("=/=" | "/=", TestVar(v), TestAtom(s)) =>
        val obj = aMap.getOrElse(v, VarObj(v))
        val pos = Pos(obj, AtomLitType(s))
        val neg = Neg(obj, AtomLitType(s))
        (neg, pos)
      case _ =>
        (Unknown, Unknown)
    }
  }

  private def negateGuardProp(prop: Prop): Prop =
    prop match {
      case Unknown =>
        Unknown
      case And(props) =>
        Or(props.map(negateGuardProp))
      case Or(props) =>
        And(props.map(negateGuardProp))
      case Pos(obj, t) =>
        Neg(obj, t)
      case Neg(obj, t) =>
        Pos(obj, t)
    }

  private def patProps(x: String, path: Path, pat: Pat, env: Env): Option[(Prop, Prop)] = {
    pat match {
      case PatWild() =>
        None
      case PatVar(v) =>
        env.get(v) map { _ => (Unknown, Unknown) }
      case PatAtom(s) =>
        val obj = mkObj(x, path)
        val pos = Pos(obj, AtomLitType(s))
        val neg = Neg(obj, AtomLitType(s))
        Some(pos, neg)
      case PatNumber() =>
        val obj = mkObj(x, path)
        val pos = Pos(obj, NumberType)
        Some(pos, Unknown)
      case PatInt() =>
        val obj = mkObj(x, path)
        val pos = Pos(obj, NumberType)
        Some(pos, Unknown)
      case PatTuple(elems) =>
        val arity = elems.size
        val obj = mkObj(x, path)
        val posThis = Pos(obj, TupleType(List.fill(arity)(AnyType)))
        val negThis = Neg(obj, TupleType(List.fill(arity)(AnyType)))
        val (posThat, negThat) = elems.zipWithIndex.flatMap { case (elem, i) =>
          patProps(x, path :+ TupleField(i, arity), elem, env)
        }.unzip
        val pos = And(posThis :: posThat)
        val neg =
          if (negThat.isEmpty) negThis
          else Or(List(negThis, And(List(posThis, Or(negThat)))))
        Some(pos, neg)
      case PatRecord(recName, fields, gen) =>
        val obj = mkObj(x, path)
        val posThis = Pos(obj, RecordType(recName)(module))
        val negThis = Neg(obj, RecordType(recName)(module))
        val (posNamed, negNamed) =
          fields.flatMap(field => patProps(x, path :+ RecordField(field.name, recName), field.pat, env)).unzip
        val (posThat, negThat) = gen match {
          case None => (posNamed, negNamed)
          case Some(genPat) =>
            val recDecl = util.getRecord(module, recName)
            recDecl match {
              case None => (posNamed, negNamed)
              case Some(recDecl) =>
                val (posGen, negGen) =
                  recDecl.fields
                    .filter(fDecl => !fields.exists(f => f.name == fDecl._1))
                    .flatMap(fDecl => patProps(x, path :+ RecordField(fDecl._1, recName), genPat, env))
                    .toList
                    .unzip
                (posNamed ++ posGen, negNamed ++ negGen)
            }
        }
        val pos = And(posThis :: posThat)
        val neg =
          if (negThat.isEmpty) negThis
          else Or(List(negThis, And(List(posThis, Or(negThat)))))
        Some(pos, neg)
      case PatMatch(PatVar(alias), pat1) =>
        env.get(alias) match {
          case Some(_) =>
            Some(Unknown, Unknown)
          case None =>
            patProps(x, path, pat1, env)
        }
      case PatMatch(pat1, PatVar(alias)) =>
        env.get(alias) match {
          case Some(_) =>
            Some(Unknown, Unknown)
          case None =>
            patProps(x, path, pat1, env)
        }
      case _ =>
        Some(Unknown, Unknown)
    }
  }

  private def simpleOverlap(t1: Type, t2: Type): Option[Boolean] =
    (kind(t1), kind(t2)) match {
      case (Some(k1), Some(k2)) =>
        Some(k1 == k2)
      case (_, _) =>
        if (subtype.subType(t1, t2) || subtype.subType(t2, t1))
          Some(true)
        else
          None
    }

  private def overlap(t1: Type, t2: Type): Option[Boolean] =
    (t1, t2) match {
      case (AnyType, _) =>
        Some(true)
      case (_, AnyType) =>
        Some(true)
      case (NoneType, _) =>
        Some(false)

      case (DynamicType, _) =>
        Some(true)

      case (VarType(_), _) =>
        Some(true)

      // Unions
      case (UnionType(ts), t) =>
        val subs = ts.map(overlap(_, t))
        if (subs.exists(_.isTrue))
          Some(true)
        else if (subs.forall(_.isFalse))
          Some(false)
        else
          None
      case (t, UnionType(ts)) =>
        val subs = ts.map(overlap(t, _))
        if (subs.exists(_.isTrue))
          Some(true)
        else if (subs.forall(_.isFalse))
          Some(false)
        else
          None

      case (AtomLitType(l1), AtomLitType(l2)) =>
        Some(l1 == l2)

      case (RemoteType(rid, args), _) =>
        val body = util.getTypeDeclBody(rid, args)
        overlap(body, t2)
      case (OpaqueType(_, _), _) =>
        None
      // $COVERAGE-OFF$
      // t2 is generated from "predicates" - they are always without aliases
      case (_, RemoteType(_, _)) =>
        throw new IllegalStateException(t2.toString)
      case (_, OpaqueType(_, _)) =>
        throw new IllegalStateException(t2.toString)
      // $COVERAGE-ON$

      // funs
      case (FunType(_, ins1, _), FunType(_, ins2, _)) =>
        if (ins1.size != ins2.size)
          Some(false)
        else
          None
      case (FunType(_, _, _), AnyFunType) =>
        Some(true)
      case (AnyFunType, FunType(_, _, _)) =>
        Some(true)
      case (FunType(_, _, _), _) =>
        Some(false)
      case (_, FunType(_, _, _)) =>
        Some(false)
      case (AnyFunType, _) =>
        Some(false)
      case (_, AnyFunType) =>
        Some(false)

      // tuples and records
      case (TupleType(ts1), TupleType(ts2)) =>
        if (ts1.size != ts2.size)
          Some(false)
        else {
          ts2 match {
            case hd :: tl =>
              // t2 comes from propositions
              assert(tl.forall(subtype.subType(AnyType, _)))
              overlap(ts1.head, hd)
            case Nil =>
              Some(true)
          }
        }
      case (TupleType(_), AnyTupleType) =>
        Some(true)
      case (AnyTupleType, TupleType(_)) =>
        Some(true)
      case (RecordType(_), AnyTupleType) =>
        Some(true)
      case (RefinedRecordType(_, _), AnyTupleType) =>
        Some(true)
      case (AnyTupleType, RecordType(_)) =>
        Some(true)
      case (RecordType(n1), RecordType(n2)) =>
        Some(n1 == n2)
      case (RefinedRecordType(t, _), RecordType(n)) =>
        Some(n == t.name)
      case (RecordType(name), TupleType(elems)) =>
        elems match {
          case Nil =>
            Some(false)
          case h :: _ =>
            overlap(AtomLitType(name), h)
        }
      case (TupleType(elems), RecordType(name)) =>
        elems match {
          case Nil =>
            Some(false)
          case h :: _ =>
            overlap(h, AtomLitType(name))
        }
      case (RefinedRecordType(t, _), TupleType(elems)) =>
        elems match {
          case Nil =>
            Some(false)
          case h :: _ =>
            overlap(AtomLitType(t.name), h)
        }
      case (TupleType(_), _) =>
        Some(false)
      case (_, TupleType(_)) =>
        Some(false)
      case (AnyTupleType, _) =>
        Some(false)
      case (_, AnyTupleType) =>
        Some(false)

      // $COVERAGE-OFF$
      case (_, RefinedRecordType(_, _)) =>
        // t2 comes from props
        throw new IllegalStateException(t2.toString)
      // $COVERAGE-ON$

      case (_: BuiltinType, _: BuiltinType) =>
        Some(subtype.subType(t1, t2) || subtype.subType(t2, t1))

      case (ListType(_) | NilType, ListType(_) | NilType) =>
        Some(true)
      case (ListType(_) | NilType, _) =>
        Some(false)
      case (_, ListType(_) | NilType) =>
        Some(false)

      case (BinaryType, BinaryType) =>
        Some(true)
      case (BinaryType, _) =>
        Some(false)
      case (_, BinaryType) =>
        Some(false)

      case _ =>
        simpleOverlap(t1, t2)
    }

  private def restrict(t1: Type, t2: Type): Type = {
    (t1, t2) match {
      case (UnionType(ts), s) =>
        UnionType(ts.map(restrict(_, s)))
      case (t, s) if overlap(t, s).isFalse =>
        NoneType
      case (t, s) if subtype.subType(t, s) =>
        t
      case (t, s) if subtype.subType(s, t) =>
        s
      case (RemoteType(rid, args), _) =>
        val body = util.getTypeDeclBody(rid, args)
        restrict(body, t2)
      case (OpaqueType(_, _), _) =>
        t1
      case (_, _) =>
        t1
    }
  }

  def remove(t1: Type, t2: Type): Type =
    (t1, t2) match {
      case (t, s) if subtype.subType(t, s) =>
        NoneType
      case (RemoteType(rid, args), _) =>
        val body = util.getTypeDeclBody(rid, args)
        remove(body, t2)
      case (OpaqueType(_, _), _) =>
        t1
      case (UnionType(ts), s) =>
        UnionType(ts.map(remove(_, s)))
      case (t, _) =>
        t
    }

  private def TupleType_*(elems: List[Type]): Type =
    if (elems.exists(subtype.isNoneType))
      NoneType
    else
      TupleType(elems)

  private def refineRecord(t: Type, field: String, refined: Type): Type = {
    if (subtype.isNoneType(refined)) {
      NoneType
    } else {
      t match {
        case rt: RefinedRecordType =>
          RefinedRecordType(rt.recType, rt.fields.updated(field, refined))
        case rt: RecordType =>
          RefinedRecordType(rt, Map(field -> refined))
        case _ => t
      }
    }
  }

  private def update(t: Type, path: Path, pol: Polarity, s: Type): Type =
    (t, path) match {
      case (_, Nil) =>
        pol match {
          case + => restrict(t, s)
          case - => remove(t, s)
        }
      case (RemoteType(rid, args), _) =>
        val body = util.getTypeDeclBody(rid, args)
        update(body, path, pol, s)
      case (UnionType(ts), _) =>
        UnionType(ts.map(update(_, path, pol, s)))
      case (TupleType(ts), TupleField(pos, arity) :: path) if ts.size == arity =>
        val t = ts(pos)
        val t1 = update(t, path, pol, s)
        TupleType_*(ts.updated(pos, t1))
      case (rt: RecordType, RecordField(fieldName, recName) :: path) if rt.name == recName =>
        util.getRecord(rt.module, rt.name) match {
          case Some(recDecl) =>
            val t = recDecl.fields(fieldName).tp
            val t1 = update(t, path, pol, s)
            refineRecord(rt, fieldName, t1)
          case _ => rt
        }
      case (rt: RefinedRecordType, RecordField(fieldName, recName) :: path) if rt.recType.name == recName =>
        if (rt.fields.contains(fieldName)) {
          val t = rt.fields(fieldName)
          val t1 = update(t, path, pol, s)
          refineRecord(rt, fieldName, t1)
        } else {
          util.getRecord(rt.recType.module, rt.recType.name) match {
            case Some(recDecl) =>
              val t = recDecl.fields(fieldName).tp
              val t1 = update(t, path, pol, s)
              refineRecord(rt, fieldName, t1)
            case None => rt
          }
        }
      case (_, _) =>
        t
    }

  private def batchSelect(typeEnv: Env, propEnv: PropEnv, aMap: AMap): Env = {
    val dnfs = dnf(propEnv, List((Nil, Nil)))
    var result: Env = Map.empty
    val names = typeEnv.keySet ++ aMap.keySet
    for (name <- names) {
      val ts = aMap.get(name) match {
        case Some(obj) =>
          val id = objId(obj)
          val path = objPath(obj)
          dnfs.map(chooseType(typeEnv, id, _)).map(typePathRef(_, path))
        case None =>
          dnfs.map(chooseType(typeEnv, name, _))
      }
      val t = ts match {
        case List(t1) => t1
        case _        => subtype.join(ts)
      }
      result += name -> t
    }
    result
  }

  private def dnf(props: List[Prop], pairs: List[PosNeg]): List[PosNeg] =
    props match {
      case Nil =>
        pairs
      case Unknown :: props =>
        dnf(props, pairs)
      case Pos(x, t) :: props =>
        dnf(props, pairs.map { case (pos, neg) => (pos :+ Pos(x, t), neg) })
      case Neg(x, t) :: props =>
        dnf(props, pairs.map { case (pos, neg) => (pos, neg :+ Neg(x, t)) })
      case And(ps) :: props =>
        dnf(ps ++ props, pairs)
      case Or(ps) :: props =>
        ps.flatMap(p => dnf(p :: props, pairs))
    }

  private def chooseType(typeEnv: Env, x: String, pair: PosNeg): Type = {
    var te = typeEnv
    for (Pos(oT, t) <- pair._1)
      te = updateTypeEnv(te, +, oT, t)
    for (Neg(oT, t) <- pair._2)
      te = updateTypeEnv(te, -, oT, t)
    te(x)
  }

  private def updateTypeEnv(typeEnv: Env, pol: Polarity, obj: Obj, t: Type): Env = {
    val x = objId(obj)
    typeEnv.get(x) match {
      case None =>
        typeEnv
      case Some(old) =>
        val s = update(old, objPath(obj), pol, t)
        typeEnv.updated(x, s)
    }
  }

  @tailrec
  private def objId(obj: Obj): String =
    obj match {
      case VarObj(v)      => v
      case FieldObj(_, o) => objId(o)
    }

  private def objPath(obj: Obj): Path =
    obj match {
      case VarObj(_) =>
        List.empty
      case FieldObj(field, obj) =>
        field :: objPath(obj)
    }

  private def typePathRef(t: Type, path: Path): Type =
    (t, path) match {
      case (NoneType, _) =>
        NoneType
      case (s, Nil) =>
        s
      case (UnionType(ts), _) =>
        UnionType(ts.map(typePathRef(_, path)))
      case (TupleType(ts), TupleField(index, arity) :: path1) if ts.size == arity =>
        typePathRef(ts(index), path1)
      case (rTy: RecordType, RecordField(fieldName, recName) :: path1) if rTy.name == recName =>
        util
          .getRecord(rTy.module, rTy.name)
          .map(_.fields(fieldName).tp)
          .map(typePathRef(_, path1))
          .getOrElse(AnyType)
      case (rTy: RefinedRecordType, RecordField(fieldName, recName) :: path1) if rTy.recType.name == recName =>
        if (rTy.fields.contains(fieldName)) {
          typePathRef(rTy.fields(fieldName), path1)
        } else {
          util
            .getRecord(rTy.recType.module, rTy.recType.name)
            .map(_.fields(fieldName).tp)
            .map(typePathRef(_, path1))
            .getOrElse(AnyType)
        }
      case (RemoteType(rid, args), path) =>
        val body = util.getTypeDeclBody(rid, args)
        typePathRef(body, path)
      case _ =>
        AnyType
    }

  private def mkObj(v: String, path: Path): Obj =
    path match {
      case Nil =>
        VarObj(v)
      case field :: path =>
        FieldObj(field, mkObj(v, path))
    }
}