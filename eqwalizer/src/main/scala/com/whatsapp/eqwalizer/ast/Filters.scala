/* Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved.
 *
 * This source code is licensed under the Apache 2.0 license found in
 * the LICENSE file in the root directory of this source tree.
 */

package com.whatsapp.eqwalizer.ast

import com.whatsapp.eqwalizer.ast.Exprs._
import com.whatsapp.eqwalizer.ast.Guards._

object Filters {
  def asTest(expr: Expr): Option[Test] =
    expr match {
      case Var(n) =>
        Some(TestVar(n)(expr.pos))
      case AtomLit(s) =>
        Some(TestAtom(s)(expr.pos))
      case IntLit(i) =>
        Some(TestNumber(Some(i))(expr.pos))
      case RemoteCall(RemoteId("erlang", f, arity), args) =>
        for {
          argsT <- asTests(args)
        } yield TestCall(Id(f, arity), argsT)(expr.pos)
      case UnOp(op, arg) =>
        for {
          argT <- asTest(arg)
        } yield TestUnOp(op, argT)(expr.pos)
      case BinOp(op, arg1, arg2) =>
        for {
          arg1T <- asTest(arg1)
          arg2T <- asTest(arg2)
        } yield TestBinOp(op, arg1T, arg2T)(expr.pos)
      case _ =>
        None
    }

  def asTests(exprs: List[Expr]): Option[List[Test]] = {
    val maybeRes = exprs.flatMap(asTest)
    if (maybeRes.length == exprs.size)
      Some(maybeRes)
    else
      None
  }
}