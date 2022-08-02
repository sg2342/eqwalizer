/* Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved.
 *
 * This source code is licensed under the Apache 2.0 license found in
 * the LICENSE file in the root directory of this source tree.
 */

package com.whatsapp.eqwalizer.analyses

import com.whatsapp.eqwalizer.{Pipeline, config}
import com.whatsapp.eqwalizer.ast.{App, Forms}
import com.whatsapp.eqwalizer.ast.stub.DbApi

object Numbers {
  val listener = new NumbersListener

  def main(args: Array[String]): Unit = {
    // $COVERAGE-OFF$
    if (args.contains("-otp")) DbApi.otpApps.values.foreach(analyzeApp)
    // $COVERAGE-ON$
    DbApi.depApps.values.foreach(analyzeApp)
    DbApi.projectApps.values.foreach(analyzeApp)
    printSummary()
  }

  private def printPct(description: String, numerator: Int, denominator: Int): Unit = {
    val pct = if (denominator == 0) 0 else (numerator * 100.0 / denominator)
    Console.println(f"    $description: $pct%1.2f%%")
  }

  private def printSummary(): Unit = {
    val stats = listener.getStats
    val total = listener.getTotal
    val nonGeneratedSpecCnt = total.specCnt - total.generatedSpecCnt
    Console.println("all specs:")
    stats.foreach { stat =>
      printPct(s"containing ${stat.name}", stat.specCnt, total.specCnt)
    }
    Console.println("generated specs:")
    stats.foreach { stat =>
      printPct(s"containing ${stat.name}", stat.generatedSpecCnt, total.generatedSpecCnt)
    }
    Console.println("non-generated specs:")
    stats.foreach { stat =>
      printPct(s"containing ${stat.name}", stat.specCnt - stat.generatedSpecCnt, nonGeneratedSpecCnt)
    }
    Console.println("all records:")
    stats.foreach { stat =>
      printPct(s"containing ${stat.name}", stat.recCnt, total.recCnt)
    }
  }

  private def analyzeApp(app: App): Unit =
    if (!config.depApps(app.name)) {
      app.modules.foreach(analyzeModule)
    }

  private def analyzeModule(module: String): Unit = {
    val astStorage = DbApi.getAstStorage(module).get
    val forms = Forms.load(astStorage)
    Pipeline.traverseForms(forms, listener)
  }
}