package org.prebid.server.analytics.reporter.greenbids;

import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Format;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Site;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import org.prebid.server.auction.model.AuctionContext;
import org.prebid.server.auction.model.BidRejectionReason;
import org.prebid.server.auction.model.BidRejectionTracker;
import org.prebid.server.model.HttpRequestContext;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Set;

public class GreenbidsAuctionContext {
    public static AuctionContext setupAuctionContext() {
        // bid request
        Site site = Site.builder()
                .domain("www.leparisien.fr")
                .build();

        Format format = Format.builder()
                .w(320)
                .h(50)
                .build();

        Imp imp = Imp.builder()
                .id("imp1")
                .banner(
                        Banner.builder()
                                .format(Collections.singletonList(format))
                                .build()
                )
                .tagid("tag1")
                .build();

        BidRequest bidRequest = BidRequest.builder()
                .id("request1")
                .imp(Collections.singletonList(imp))
                .site(site)
                .build();

        // bid response
        Bid bid = Bid.builder()
                .id("bid1")
                .impid("imp1")
                .price(BigDecimal.valueOf(1.5))
                .adm("<div>Ad Markup</div>")
                .build();

        SeatBid seatBidWithBid = SeatBid.builder()
                .bid(Collections.singletonList(bid))
                .seat("seat1")
                .build();

        BidResponse bidResponse = BidResponse.builder()
                .id("response1")
                .seatbid(Collections.singletonList(seatBidWithBid))
                .cur("USD")
                .build();

        BidRejectionTracker bidRejectionTracker = new BidRejectionTracker(
                "seat2",
                Set.of("imp1"),
                1.0
        );

        bidRejectionTracker.reject("imp1", BidRejectionReason.NO_BID);

        return AuctionContext.builder()
                .httpRequest(HttpRequestContext.builder().build())
                .bidRequest(bidRequest)
                .bidResponse(bidResponse)
                .bidRejectionTrackers(
                        Collections.singletonMap(
                                "seat2",
                                bidRejectionTracker
                        )
                )
                .build();
    }

    public static AuctionContext setupAuctionContextWithNoAdUnit() {
        // bid request
        Site site = Site.builder()
                .domain("www.leparisien.fr")
                .build();

        BidRequest bidRequest = BidRequest.builder()
                .id("request1")
                .site(site)
                .build();


        return AuctionContext.builder()
                .httpRequest(HttpRequestContext.builder().build())
                .bidRequest(bidRequest)
                .build();
    }
}