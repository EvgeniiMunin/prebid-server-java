package org.prebid.server.floors;

import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Format;
import com.iab.openrtb.request.Imp;
import org.prebid.server.floors.model.PriceFloorResult;
import org.prebid.server.floors.model.PriceFloorRules;
import org.prebid.server.proto.openrtb.ext.request.ImpMediaType;

import java.util.List;

public interface PriceFloorResolver {

    PriceFloorResult resolve(BidRequest bidRequest,
                             PriceFloorRules floorRules,
                             Imp imp,
                             ImpMediaType mediaType,
                             Format format,
                             String bidder,
                             List<String> warnings);

    default PriceFloorResult resolve(BidRequest bidRequest,
                                     PriceFloorRules floorRules,
                                     Imp imp,
                                     String bidder,
                                     List<String> warnings) {

        return resolve(bidRequest, floorRules, imp, null, null, bidder, warnings);
    }

    static NoOpPriceFloorResolver noOp() {
        return new NoOpPriceFloorResolver();
    }

    class NoOpPriceFloorResolver implements PriceFloorResolver {

        @Override
        public PriceFloorResult resolve(BidRequest bidRequest,
                                        PriceFloorRules floorRules,
                                        Imp imp,
                                        ImpMediaType mediaType,
                                        Format format,
                                        String bidder,
                                        List<String> warnings) {

            return null;
        }
    }
}
