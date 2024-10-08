package org.prebid.server.functional.model.request.auction

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import groovy.transform.ToString

@ToString(includeNames = true, ignoreNulls = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy)
class Regs {

    Integer coppa
    Integer gdpr
    String usPrivacy
    String gpp
    List<Integer> gppSid
    RegsExt ext

    static Regs getDefaultRegs() {
        new Regs().tap {
            gdpr = 0
        }
    }
}
