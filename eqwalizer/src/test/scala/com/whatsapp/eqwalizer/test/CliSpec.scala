/* Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved.
 *
 * This source code is licensed under the Apache 2.0 license found in
 * the LICENSE file in the root directory of this source tree.
 */

package com.whatsapp.eqwalizer.test

import com.whatsapp.eqwalizer.Main
import com.whatsapp.eqwalizer.analyses._

class CliSpec extends SnapshotSpec {
  describe("eqwalizer") {
    it("prints help by default") {
      checkAction(Main.main(Array()), "help.cli")
    }

    it("prints help for command that is too short") {
      checkAction(Main.main(Array("check")), "help.cli")
    }

    it("prints help for wrong command") {
      checkAction(Main.main(Array("do_it", "misc")), "help.cli")
    }

    it("type checks a single module") {
      checkAction(Main.main(Array("check", "as_pat")), "check02.cli")
    }

    it("dies hard with a missing ast file") {
      intercept[IllegalArgumentException] {
        Main.main(Array("check", "missing"))
      }
    }

    it("dies hard (for ELP) with a missing ast file") {
      intercept[IllegalArgumentException] {
        Main.main(Array("check", "--json", "missing"))
      }
    }

    it("reports pinned vars") {
      checkAction(PinnedVars.main(Array()), "pinned.cli")
    }

    it("reports refined record types") {
      checkAction(RefinedRecordTypes.main(Array()), "refined_record_types.cli")
    }

    it("report bad prop types") {
      checkAction(BadPropTypes.main(Array()), "bad_prop_types.cli")
    }

    it("reports overloaded fun specs") {
      checkAction(OverloadedFunSpecs.main(Array()), "overloaded_fun_specs.cli")
    }

    it("reports unions with type variables") {
      checkAction(UnionsWithTypeVars.main(Array()), "unions_with_type_vars.cli")
    }

    it("reports FIXME specs") {
      checkAction(DiscardedSpecs.main(Array()), "discarded_specs.cli")
    }

    it("reports clauses with repeated variable names") {
      checkAction(RepeatedVars.main(Array()), "repeated_vars.cli")
    }

    it("reports OTP function calls") {
      checkAction(OTPFuns.main(Array()), "otp_funs.cli")
    }

    it("reports forms") {
      checkAction(Invalids.main(Array()), "invalids.cli")
    }

    it("prints ELP diagnostics") {
      checkAction(Main.main(Array("check", "refine", "--json")), "refine.elp.json")
      checkAction(Main.main(Array("check", "opaque", "--json")), "opaque.elp.json")
    }
  }
}
