package org.prebid.server.bidder.smaato;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.iab.openrtb.request.App;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Dooh;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Native;
import com.iab.openrtb.request.Publisher;
import com.iab.openrtb.request.Site;
import com.iab.openrtb.request.User;
import com.iab.openrtb.request.Video;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import io.vertx.core.MultiMap;
import org.apache.commons.lang3.ObjectUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.prebid.server.VertxTest;
import org.prebid.server.auction.model.Endpoint;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderCall;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.HttpResponse;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.bidder.smaato.proto.SmaatoBidExt;
import org.prebid.server.bidder.smaato.proto.SmaatoBidRequestExt;
import org.prebid.server.bidder.smaato.proto.SmaatoSiteExtData;
import org.prebid.server.bidder.smaato.proto.SmaatoUserExtData;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtRequest;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebidServer;
import org.prebid.server.proto.openrtb.ext.request.ExtSite;
import org.prebid.server.proto.openrtb.ext.request.ExtUser;
import org.prebid.server.proto.openrtb.ext.request.smaato.ExtImpSmaato;
import org.prebid.server.proto.openrtb.ext.response.ExtBidPrebidVideo;
import org.prebid.server.util.HttpUtil;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

import static java.util.Collections.singletonList;
import static java.util.function.UnaryOperator.identity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;
import static org.prebid.server.proto.openrtb.ext.response.BidType.banner;
import static org.prebid.server.proto.openrtb.ext.response.BidType.video;
import static org.prebid.server.proto.openrtb.ext.response.BidType.xNative;

@ExtendWith(MockitoExtension.class)
public class SmaatoBidderTest extends VertxTest {

    private static final String ENDPOINT_URL = "https://test.endpoint.com";

    @Mock
    private Clock clock;

    private SmaatoBidder target;

    @BeforeEach
    public void setUp() {
        target = new SmaatoBidder(ENDPOINT_URL, jacksonMapper, clock);
    }

    @Test
    public void creationShouldFailOnInvalidEndpointUrl() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SmaatoBidder("invalid_url", jacksonMapper, clock));
    }

    @Test
    public void makeHttpRequestsShouldModifyUserIfUserExtDataIsPresent() {
        // given
        final BidRequest bidRequest = givenBidRequest(bidRequestBuilder ->
                bidRequestBuilder.user(User.builder()
                        .ext(ExtUser.builder()
                                .data(mapper.valueToTree(SmaatoUserExtData.of("keywords", "gender", 1)))
                                .build())
                        .build()), identity());

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(HttpRequest::getPayload)
                .extracting(BidRequest::getUser)
                .extracting(User::getKeywords, User::getGender, User::getYob)
                .containsExactly(tuple("keywords", "gender", 1));
    }

    @Test
    public void makeHttpRequestsShouldModifySiteIfSiteExtDataIsPresent() {
        // given
        final BidRequest bidRequest = givenBidRequest(bidRequestBuilder ->
                bidRequestBuilder.site(Site.builder()
                        .ext(ExtSite.of(1, mapper.valueToTree(SmaatoSiteExtData.of("keywords"))))
                        .build()), identity());
        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(HttpRequest::getPayload)
                .extracting(BidRequest::getSite)
                .extracting(Site::getKeywords, Site::getExt)
                .containsExactly(tuple("keywords", null));
    }

    @Test
    public void makeHttpRequestsShouldSetExt() {
        // given
        final BidRequest bidRequest = givenBidRequest();

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(HttpRequest::getPayload)
                .extracting(BidRequest::getExt)
                .containsExactly(jacksonMapper.fillExtension(ExtRequest.empty(),
                        SmaatoBidRequestExt.of("prebid_server_1.2")));
    }

    @Test
    public void makePodHttpRequestsShouldReturnErrorsForImpsOfInvalidMediaType() {
        // given
        final BidRequest bidRequest = givenVideoBidRequest(identity(), impBuilder -> impBuilder.video(null));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getValue()).isEmpty();
        assertThat(result.getErrors()).containsExactly(
                BidderError.badInput("Invalid MediaType. Smaato only supports Video for AdPod."));
    }

    @Test
    public void makePodHttpRequestsShouldCorrectlyConstructImpPodsAndRequests() {
        // given
        final BidRequest bidRequest = givenVideoBidRequest(
                identity(),
                impBuilder -> impBuilder.id("1_0"),
                impBuilder -> impBuilder.id("1_1"),
                impBuilder -> impBuilder.id("2_0"));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        final List<HttpRequest<BidRequest>> requests = result.getValue();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).getPayload().getImp())
                .extracting(Imp::getId)
                .containsExactlyInAnyOrder("1_0", "1_1");
        assertThat(requests.get(1).getPayload().getImp())
                .extracting(Imp::getId)
                .containsExactly("2_0");
    }

    @Test
    public void makePodHttpRequestsShouldReturnErrorIfImpExtCouldNotBeParsed() {
        // given
        final BidRequest bidRequest = givenVideoBidRequest(
                impBuilder -> impBuilder.ext(mapper.valueToTree(ExtPrebid.of(null, mapper.createArrayNode()))));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getValue()).isEmpty();
        assertThat(result.getErrors()).hasSize(1)
                .allSatisfy(bidderError -> {
                    assertThat(bidderError.getType()).isEqualTo(BidderError.Type.bad_input);
                    assertThat(bidderError.getMessage()).startsWith("Cannot deserialize value");
                });
    }

    @Test
    public void makePodHttpRequestsShouldReturnErrorIfImpExtPublisherIdIsAbsent() {
        // given
        final BidRequest bidRequest = givenVideoBidRequest(impBuilder ->
                impBuilder.ext(mapper.valueToTree(ExtPrebid.of(null,
                        ExtImpSmaato.of(null, null, "adbreakId")))));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getValue()).isEmpty();
        assertThat(result.getErrors()).containsExactly(BidderError.badInput("Missing publisherId property."));
    }

    @Test
    public void makePodHttpRequestsShouldReturnErrorIfImpExtAdBreakIdIsAbsent() {
        // given
        final BidRequest bidRequest = givenVideoBidRequest(impBuilder ->
                impBuilder.ext(mapper.valueToTree(ExtPrebid.of(null, ExtImpSmaato.of(
                        "publisherId", null, null)))));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getValue()).isEmpty();
        assertThat(result.getErrors()).containsExactly(BidderError.badInput("Missing adbreakId property."));
    }

    @Test
    public void makePodHttpRequestsShouldEnrichSiteWithPublisherIdIfSiteIsPresentInRequest() {
        // given
        final BidRequest bidRequest = givenVideoBidRequest(
                bidRequestBuilder -> bidRequestBuilder.site(Site.builder().build()).app(null),
                impBuilder -> impBuilder.ext(mapper.valueToTree(ExtPrebid.of(null,
                        ExtImpSmaato.of("publisherId", null, "adBreakId")))));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(HttpRequest::getPayload)
                .extracting(BidRequest::getSite)
                .extracting(Site::getPublisher)
                .extracting(Publisher::getId)
                .containsExactly("publisherId");
    }

    @Test
    public void makePodHttpRequestsShouldEnrichAppWithPublisherIdIfSiteIsAbsentAndAppIsPresentInRequest() {
        // given
        final BidRequest bidRequest = givenVideoBidRequest(
                bidRequestBuilder -> bidRequestBuilder.site(null).app(App.builder().build()),
                impBuilder -> impBuilder.ext(mapper.valueToTree(ExtPrebid.of(null,
                        ExtImpSmaato.of("publisherId", null, "adBreakId")))));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(HttpRequest::getPayload)
                .extracting(BidRequest::getApp)
                .extracting(App::getPublisher)
                .extracting(Publisher::getId)
                .containsExactly("publisherId");
    }

    @Test
    public void makePodHttpRequestsShouldReturnErrorIfSiteAndAppAreAbsentInRequest() {
        // given
        final BidRequest bidRequest = givenVideoBidRequest(bidRequestBuilder ->
                bidRequestBuilder.site(null).app(null), identity());

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getValue()).isEmpty();
        assertThat(result.getErrors())
                .containsExactly(BidderError.badInput("Missing Site/App/DOOH."));
    }

    @Test
    public void makePodHttpRequestsShouldCorrectlyModifyImps() {
        // given
        final BidRequest bidRequest = givenVideoBidRequest(
                identity(),
                impBuilder -> impBuilder.id("1_0"),
                impBuilder -> impBuilder.id("1_1"));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        final BiFunction<Imp.ImpBuilder, Integer, Imp.ImpBuilder> resultCustomizer =
                (builder, idx) -> builder
                        .id("1_" + idx)
                        .tagid("adbreakId")
                        .ext(null)
                        .video(Video.builder()
                                .ext(mapper.createObjectNode().set("context", TextNode.valueOf("adpod")))
                                .sequence(idx + 1)
                                .build());

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(HttpRequest::getPayload)
                .flatExtracting(BidRequest::getImp)
                .containsExactlyInAnyOrder(
                        givenVideoImp(impBuilder -> resultCustomizer.apply(impBuilder, 0)),
                        givenVideoImp(impBuilder -> resultCustomizer.apply(impBuilder, 1)));
    }

    @Test
    public void makeIndividualHttpRequestsShouldReturnErrorsOfImpsWithInvalidMediaTypes() {
        // given
        final BidRequest bidRequest = givenBidRequest(impBuilder -> impBuilder.video(null).banner(null).xNative(null));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors())
                .containsExactly(
                        BidderError.badInput("Invalid MediaType. Smaato only supports Banner, Video and Native."));
    }

    @Test
    public void makeIndividualHttpRequestsShouldCorrectlySplitImps() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                identity(),
                impBuilder -> impBuilder
                        .id("123")
                        .banner(Banner.builder().w(1).h(1).build())
                        .video(Video.builder().w(1).h(1).build())
                        .xNative(Native.builder().build()),
                impBuilder -> impBuilder.id("456").banner(Banner.builder().w(1).h(1).build()));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).hasSize(4)
                .extracting(HttpRequest::getPayload)
                .flatExtracting(BidRequest::getImp)
                .allMatch(imp -> ObjectUtils.anyNotNull(imp.getVideo(), imp.getBanner(), imp.getXNative()));

    }

    @Test
    public void makeHttpShouldPassthouthImpExt() {
        // given
        final ObjectNode impExt = mapper.createObjectNode()
                .set("bidder", mapper.createObjectNode()
                        .put("publisherId", "publisherId")
                        .put("adspaceId", "adspaceId")
                        .put("adbreakId", "adbreakId"));

        impExt.set("skadn", mapper.createObjectNode()
                .put("fieldOne", "123")
                .put("fieldTwo", "321"));

        impExt.set("gpid", IntNode.valueOf(1));

        // and
        final BidRequest bidRequest = givenBidRequest(identity(), impBuilder -> impBuilder.ext(impExt));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        final ObjectNode expectedImpExt = mapper.createObjectNode();
        expectedImpExt.set("gpid", IntNode.valueOf(1));
        expectedImpExt.set("skadn", impExt.get("skadn").deepCopy());

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).hasSize(1)
                .extracting(HttpRequest::getPayload)
                .flatExtracting(BidRequest::getImp)
                .extracting(Imp::getExt)
                .containsExactly(expectedImpExt);
    }

    @Test
    public void makeIndividualHttpRequestsShouldReturnErrorIfImpExtCouldNotBeParsed() {
        // given
        final BidRequest bidRequest = givenBidRequest(impBuilder ->
                impBuilder.ext(mapper.valueToTree(ExtPrebid.of(null, mapper.createArrayNode()))));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getValue()).isEmpty();
        assertThat(result.getErrors()).hasSize(1)
                .allSatisfy(bidderError -> {
                    assertThat(bidderError.getType()).isEqualTo(BidderError.Type.bad_input);
                    assertThat(bidderError.getMessage()).startsWith("Cannot deserialize value");
                });
    }

    @Test
    public void makeIndividualHttpRequestsShouldReturnErrorIfImpExtPublisherIdIsAbsent() {
        // given
        final BidRequest bidRequest = givenBidRequest(impBuilder ->
                impBuilder.ext(mapper.valueToTree(ExtPrebid.of(null,
                        ExtImpSmaato.of(null, "adspaceId", null)))));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getValue()).isEmpty();
        assertThat(result.getErrors()).containsExactly(BidderError.badInput("Missing publisherId property."));
    }

    @Test
    public void makeIndividualHttpRequestsShouldReturnErrorIfImpExtAdSpaceIdIsAbsent() {
        // given
        final BidRequest bidRequest = givenBidRequest(impBuilder ->
                impBuilder.ext(mapper.valueToTree(ExtPrebid.of(null, ExtImpSmaato.of(
                        "publisherId", null, null)))));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getValue()).isEmpty();
        assertThat(result.getErrors()).containsExactly(BidderError.badInput("Missing adspaceId property."));
    }

    @Test
    public void makeIndividualHttpRequestsShouldEnrichSiteWithPublisherIdIfSiteIsPresentInRequest() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                bidRequestBuilder -> bidRequestBuilder.site(Site.builder().build()).app(null),
                impBuilder -> impBuilder.ext(mapper.valueToTree(ExtPrebid.of(null,
                        ExtImpSmaato.of("publisherId", "adspaceId", null)))));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(HttpRequest::getPayload)
                .extracting(BidRequest::getSite)
                .extracting(Site::getPublisher)
                .extracting(Publisher::getId)
                .containsExactly("publisherId");
    }

    @Test
    public void makeIndividualHttpRequestsShouldEnrichAppWithPublisherIdIfSiteIsAbsentAndAppIsPresentInRequest() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                bidRequestBuilder -> bidRequestBuilder.site(null).app(App.builder().build()),
                impBuilder -> impBuilder.ext(mapper.valueToTree(ExtPrebid.of(null,
                        ExtImpSmaato.of("publisherId", "adspaceId", null)))));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(HttpRequest::getPayload)
                .extracting(BidRequest::getApp)
                .extracting(App::getPublisher)
                .extracting(Publisher::getId)
                .containsExactly("publisherId");
    }

    @Test
    public void makeIndividualHttpRequestsShouldEnrichAppWithPublisherIdIfSiteAndAppAreAbsentAndDoohIsPresent() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                bidRequestBuilder -> bidRequestBuilder.site(null).app(null).dooh(Dooh.builder().build()),
                impBuilder -> impBuilder.ext(mapper.valueToTree(ExtPrebid.of(null,
                        ExtImpSmaato.of("publisherId", "adspaceId", null)))));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(HttpRequest::getPayload)
                .extracting(BidRequest::getDooh)
                .extracting(Dooh::getPublisher)
                .extracting(Publisher::getId)
                .containsExactly("publisherId");
    }

    @Test
    public void makeIndividualHttpRequestsShouldReturnErrorIfSiteAndAppAndDoohAreAbsentInRequest() {
        // given
        final BidRequest bidRequest = givenBidRequest(bidRequestBuilder ->
                bidRequestBuilder.site(null).app(null).dooh(null), identity());

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getValue()).isEmpty();
        assertThat(result.getErrors())
                .containsExactly(BidderError.badInput("Missing Site/App/DOOH."));
    }

    @Test
    public void makeIndividualHttpRequestsShouldNotModifyBannerIfBannerSizesArePresent() {
        // given
        final BidRequest bidRequest = givenBidRequest(impBuilder ->
                impBuilder.banner(Banner.builder().w(1).h(1).build()));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(HttpRequest::getPayload)
                .flatExtracting(BidRequest::getImp)
                .extracting(Imp::getBanner)
                .containsExactly(Banner.builder().w(1).h(1).build());
    }

    @Test
    public void makeIndividualHttpRequestsShouldSetImpTagIdAndRemoveImpExt() {
        // given
        final BidRequest bidRequest = givenBidRequest(impBuilder ->
                impBuilder.ext(mapper.valueToTree(ExtPrebid.of(null,
                        ExtImpSmaato.of("publisherId", "adspaceId", null)))));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(HttpRequest::getPayload)
                .flatExtracting(BidRequest::getImp)
                .extracting(Imp::getTagid, Imp::getExt)
                .containsExactly(tuple("adspaceId", null));
    }

    @Test
    public void makeBidsShouldReturnErrorIfResponseBodyCouldNotBeParsed() {
        // given
        final BidderCall<BidRequest> httpCall = givenHttpCall(null, "invalid", null);

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        assertThat(result.getValue()).isEmpty();
        assertThat(result.getErrors()).hasSize(1)
                .allSatisfy(bidderError -> {
                    assertThat(bidderError.getType()).isEqualTo(BidderError.Type.bad_input);
                    assertThat(bidderError.getMessage()).startsWith("Failed to decode:");
                });
    }

    @Test
    public void makeBidsShouldReturnEmptyListIfBidResponseIsNull() throws JsonProcessingException {
        // given
        final BidderCall<BidRequest> httpCall = givenHttpCall(null, mapper.writeValueAsString(null), null);

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).isEmpty();
    }

    @Test
    public void makeBidsShouldReturnErrorOnEmptyBidAdm() throws JsonProcessingException {
        // given
        final BidderCall<BidRequest> httpCall = givenHttpCall(
                givenBidRequest(),
                givenBidResponse(bidBuilder -> bidBuilder.id("test")),
                HttpUtil.headers().add("X-Smt-Adtype", "anyType"));

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        assertThat(result.getValue()).isEmpty();
        assertThat(result.getErrors()).containsExactly(BidderError.badInput("Empty ad markup in bid with id: test"));
    }

    @Test
    public void makeBidsShouldReturnErrorIfNotSupportedMarkupType() throws JsonProcessingException {
        // given
        final MultiMap headers = MultiMap.caseInsensitiveMultiMap().set("X-Smt-Adtype", "anyType");
        final BidderCall<BidRequest> httpCall = givenHttpCall(
                givenBidRequest(),
                givenBidResponse(bidBuilder -> bidBuilder.adm("adm")),
                headers);

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        assertThat(result.getValue()).isEmpty();
        assertThat(result.getErrors()).containsExactly(BidderError.badInput("Invalid markupType anyType"));
    }

    @Test
    public void makeBidsShouldCalculateTtlIfExpirationHeaderIsPresentInResponse() throws JsonProcessingException {
        // given
        when(clock.millis()).thenReturn(100L);

        final MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                .set("X-Smt-Expires", String.valueOf(10000))
                .set("X-Smt-Adtype", "Img");
        final BidderCall<BidRequest> httpCall = givenHttpCall(
                givenBidRequest(),
                givenBidResponse(bidBuilder -> bidBuilder.adm("adm")),
                headers);

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(BidderBid::getBid)
                .extracting(Bid::getExp)
                .containsExactly(9);
    }

    @Test
    public void makeBidsShouldSetTtlToZeroIfExpirationHeaderIsPresentInResponseButLessThanCurrentTime()
            throws JsonProcessingException {
        // given
        when(clock.millis()).thenReturn(999999L);

        final MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                .set("X-Smt-Expires", String.valueOf(10000))
                .set("X-Smt-Adtype", "Img");

        final BidderCall<BidRequest> httpCall = givenHttpCall(
                givenBidRequest(),
                givenBidResponse(bidBuilder -> bidBuilder.adm("adm")),
                headers);

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(BidderBid::getBid)
                .extracting(Bid::getExp)
                .containsExactly(0);
    }

    @Test
    public void makeBidsShouldSetDefaultTtlIfExpirationHeaderIsAbsentInResponse() throws JsonProcessingException {
        // given
        final MultiMap headers = MultiMap.caseInsensitiveMultiMap().set("X-Smt-Adtype", "Img");
        final BidderCall<BidRequest> httpCall = givenHttpCall(
                givenBidRequest(),
                givenBidResponse(bidBuilder -> bidBuilder.adm("adm")),
                headers);

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(BidderBid::getBid)
                .extracting(Bid::getExp)
                .containsExactly(300);
    }

    @Test
    public void makeBidsShouldReturnErrorIfMarkupTypeIsBlank() throws JsonProcessingException {
        // given
        final BidderCall<BidRequest> httpCall = givenHttpCall(
                givenBidRequest(),
                givenBidResponse(bidBuilder -> bidBuilder.adm("adm")),
                MultiMap.caseInsensitiveMultiMap().set("X-Smt-Adtype", ""));

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        assertThat(result.getValue()).isEmpty();
        assertThat(result.getErrors()).containsExactly(BidderError.badInput("X-Smt-Adtype header is missing."));
    }

    @Test
    public void makeBidsShouldReturnErrorIfNativeAdmIsInvalid() throws JsonProcessingException {
        // given
        final BidderCall<BidRequest> httpCall = givenHttpCall(
                givenBidRequest(),
                givenBidResponse(bidBuilder -> bidBuilder.adm("{\"image\": invalid")),
                MultiMap.caseInsensitiveMultiMap().set("X-Smt-Adtype", "Native"));

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        assertThat(result.getValue()).isEmpty();
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().getFirst().getMessage()).startsWith("Cannot decode bid.adm:");
        assertThat(result.getErrors().getFirst().getType()).isEqualTo(BidderError.Type.bad_input);
    }

    @Test
    public void makeBidsShouldReturnCorrectBidIfAdMarkTypeIsRichmedia() throws JsonProcessingException {
        // given
        final String adm = "{\"richmedia\":{\"mediadata\":"
                + "{\"content\":\"<div>hello</div>\", \"w\":350,\"h\":50},\"impressiontrackers\":"
                + "[\"//prebid-test.smaatolabs.net/track/imp/1\",\"//prebid-test.smaatolabs.net/track"
                + "/imp/2\"],\"clicktrackers\":[\"//prebid-test.smaatolabs.net/track/click/1\","
                + "\"//prebid-test.smaatolabs.net/track/click/2\"]}}";

        final ObjectNode givenBidExt = mapper.valueToTree(SmaatoBidExt.of(100, List.of("curl1", "curl2")));
        final BidderCall<BidRequest> httpCall = givenHttpCall(
                givenBidRequest(),
                givenBidResponse(bidBuilder -> bidBuilder
                        .adm(adm)
                        .ext(givenBidExt)),
                MultiMap.caseInsensitiveMultiMap().set("X-Smt-Adtype", "Richmedia"));

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        final String expectedAdm =
                "<div style=\"cursor:pointer\" onclick=\"fetch(decodeURIComponent('curl1'.replace(/\\+/g, ' ')), "
                        + "{cache: 'no-cache'});fetch(decodeURIComponent('curl2'.replace(/\\+/g, ' ')), "
                        + "{cache: 'no-cache'});\">" + adm + "</div>";

        final Bid expectedBid = Bid.builder()
                .impid("123")
                .adm(expectedAdm)
                .ext(givenBidExt)
                .exp(300)
                .build();

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).containsExactly(BidderBid.of(expectedBid, banner, "USD"));
    }

    @Test
    public void makeBidsShouldReturnCorrectBidIfAdMarkTypeIsRichmediaAndCurlsAreAbsent()
            throws JsonProcessingException {
        // given
        final String adm = "{\"richmedia\":{\"mediadata\":"
                + "{\"content\":\"<div>hello</div>\", \"w\":350,\"h\":50},\"impressiontrackers\":"
                + "[\"//prebid-test.smaatolabs.net/track/imp/1\",\"//prebid-test.smaatolabs.net/track"
                + "/imp/2\"],\"clicktrackers\":[\"//prebid-test.smaatolabs.net/track/click/1\","
                + "\"//prebid-test.smaatolabs.net/track/click/2\"]}}";

        final BidderCall<BidRequest> httpCall = givenHttpCall(
                givenBidRequest(),
                givenBidResponse(bidBuilder -> bidBuilder.adm(adm)),
                MultiMap.caseInsensitiveMultiMap().set("X-Smt-Adtype", "Richmedia"));

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        final Bid expectedBid = Bid.builder()
                .impid("123")
                .adm(adm)
                .exp(300)
                .build();

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).containsExactly(BidderBid.of(expectedBid, banner, "USD"));
    }

    @Test
    public void makeBidsShouldReturnCorrectBidIfAdMarkTypeIsImg() throws JsonProcessingException {
        // given
        final String adm = "{\"image\":{\"img\":{\"url\":\""
                + "//prebid-test.smaatolabs.net/img/320x50.jpg\",\"w\":350,\"h\":50,\"ctaurl\":\""
                + "//prebid-test.smaatolabs.net/track/ctaurl/1\"},\"impressiontrackers\":[\""
                + "//prebid-test.smaatolabs.net/track/imp/1\",\"//prebid-test.smaatolabs.net/track/"
                + "imp/2\"],\"clicktrackers\":[\"//prebid-test.smaatolabs.net/track/click/1\",\""
                + "//prebid-test.smaatolabs.net/track/click/2\"]}}";

        final ObjectNode givenBidExt = mapper.valueToTree(SmaatoBidExt.of(100, List.of("curl1", "curl2")));
        final BidderCall<BidRequest> httpCall = givenHttpCall(
                givenBidRequest(),
                givenBidResponse(bidBuilder -> bidBuilder
                        .adm(adm)
                        .ext(givenBidExt)),
                MultiMap.caseInsensitiveMultiMap().set("X-Smt-Adtype", "Img"));

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        final String expectedAdm =
                "<div style=\"cursor:pointer\" onclick=\"fetch(decodeURIComponent('curl1'.replace(/\\+/g, ' ')), "
                        + "{cache: 'no-cache'});fetch(decodeURIComponent('curl2'.replace(/\\+/g, ' ')), "
                        + "{cache: 'no-cache'});\">" + adm + "</div>";

        final Bid expectedBid = Bid.builder()
                .impid("123")
                .adm(expectedAdm)
                .exp(300)
                .ext(givenBidExt)
                .build();

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).containsExactly(BidderBid.of(expectedBid, banner, "USD"));
    }

    @Test
    public void makeBidsShouldReturnCorrectBidIfAdMarkTypeIsImgAndCurlsAreEmpty() throws JsonProcessingException {
        // given
        final String adm = "{\"image\":{\"img\":{\"url\":\""
                + "//prebid-test.smaatolabs.net/img/320x50.jpg\",\"ctaurl\":\""
                + "//prebid-test.smaatolabs.net/track/ctaurl/1\"},\"impressiontrackers\":[\""
                + "//prebid-test.smaatolabs.net/track/imp/1\",\"//prebid-test.smaatolabs.net/track/"
                + "imp/2\"],\"clicktrackers\":[\"//prebid-test.smaatolabs.net/track/click/1\",\""
                + "//prebid-test.smaatolabs.net/track/click/2\"]}}";

        final BidderCall<BidRequest> httpCall = givenHttpCall(
                givenBidRequest(),
                givenBidResponse(bidBuilder -> bidBuilder.adm(adm)),
                MultiMap.caseInsensitiveMultiMap().set("X-Smt-Adtype", "Img"));

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        final Bid expectedBid = Bid.builder()
                .impid("123")
                .adm(adm)
                .exp(300)
                .build();

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).containsExactly(BidderBid.of(expectedBid, banner, "USD"));
    }

    @Test
    public void makeBidsShouldReturnCorrectBidIfAdMarkTypeIsVideo() throws JsonProcessingException {
        // given
        final String adm = "<?xml version=\"1.0\" encoding="
                + "\"UTF-8\" standalone=\"no\"?><VAST version=\"2.0\"></VAST>";

        final ObjectNode givenBidExt = mapper.valueToTree(SmaatoBidExt.of(100, List.of()));
        final BidderCall<BidRequest> httpCall = givenHttpCall(
                givenBidRequest(),
                givenBidResponse(bidBuilder -> bidBuilder
                        .adm(adm)
                        .cat(singletonList("Category1"))
                        .ext(mapper.valueToTree(SmaatoBidExt.of(100, List.of())))),
                MultiMap.caseInsensitiveMultiMap().set("X-SMT-ADTYPE", "Video"));

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        final Bid expectedBid = Bid.builder()
                .impid("123")
                .adm(adm)
                .cat(singletonList("Category1"))
                .ext(givenBidExt)
                .exp(300)
                .build();

        final BidderBid expectedBidderBid = BidderBid.builder()
                .bidCurrency("USD")
                .type(video)
                .bid(expectedBid)
                .videoInfo(ExtBidPrebidVideo.of(100, "Category1"))
                .build();

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).containsExactly(expectedBidderBid);
    }

    @Test
    public void makeBidsShouldReturnCorrectBidIfAdMarkTypeIsNative() throws JsonProcessingException {
        // given
        final String adm = "{\"native\": {\"assets\":[{\"id\": 1, \"img\": {\"type\": 3, "
                + "\"url\": \"https://smaato.com/image.png\", \"w\": 480, \"h\": 320}}], "
                + "\"link\": {\"url\": \"https://www.smaato.com\"}}}";

        final ObjectNode givenBidExt = mapper.valueToTree(SmaatoBidExt.of(100, List.of()));
        final BidderCall<BidRequest> httpCall = givenHttpCall(
                givenBidRequest(),
                givenBidResponse(bidBuilder -> bidBuilder
                        .adm(adm)
                        .ext(givenBidExt)),
                MultiMap.caseInsensitiveMultiMap().set("X-SMT-ADTYPE", "Native"));

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        final String expectedAdm = "{\"assets\":[{\"id\":1,\"img\":{\"type\":3,"
                + "\"url\":\"https://smaato.com/image.png\",\"w\":480,\"h\":320}}],"
                + "\"link\":{\"url\":\"https://www.smaato.com\"}}";
        final Bid expectedBid = Bid.builder()
                .impid("123")
                .adm(expectedAdm)
                .ext(givenBidExt)
                .exp(300)
                .build();

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).containsExactly(BidderBid.of(expectedBid, xNative, "USD"));
    }

    @Test
    public void makeBidsShouldReturnEmptyListIfBidResponseSeatBidIsNull() throws JsonProcessingException {
        // given
        final BidderCall<BidRequest> httpCall = givenHttpCall(null,
                mapper.writeValueAsString(BidResponse.builder().build()), null);

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).isEmpty();
    }

    private static BidRequest givenVideoBidRequest(UnaryOperator<Imp.ImpBuilder> impCustomizer) {
        return givenVideoBidRequest(identity(), impCustomizer);
    }

    private static BidRequest givenVideoBidRequest(
            UnaryOperator<BidRequest.BidRequestBuilder> bidRequestCustomizer,
            UnaryOperator<Imp.ImpBuilder>... impCustomizers) {
        return bidRequestCustomizer.apply(BidRequest.builder()
                        .site(Site.builder().build())
                        .app(App.builder().build())
                        .imp(Arrays.stream(impCustomizers)
                                .map(SmaatoBidderTest::givenVideoImp)
                                .toList()))
                .ext(ExtRequest.of(ExtRequestPrebid.builder()
                        .server(ExtRequestPrebidServer.of(null, null, null, Endpoint.openrtb2_video.value()))
                        .build()))
                .build();
    }

    private static BidRequest givenBidRequest() {
        return givenBidRequest(identity());
    }

    private static BidRequest givenBidRequest(
            UnaryOperator<BidRequest.BidRequestBuilder> bidRequestCustomizer,
            UnaryOperator<Imp.ImpBuilder>... impCustomizers) {
        return bidRequestCustomizer.apply(BidRequest.builder()
                        .site(Site.builder().build())
                        .app(App.builder().build())
                        .imp(Arrays.stream(impCustomizers)
                                .map(SmaatoBidderTest::givenImp)
                                .toList()))
                .build();
    }

    private static BidRequest givenBidRequest(UnaryOperator<Imp.ImpBuilder> impCustomizer) {
        return givenBidRequest(identity(), impCustomizer);
    }

    private static Imp givenVideoImp(UnaryOperator<Imp.ImpBuilder> impCustomizer) {
        return impCustomizer.apply(givenImp(identity()).toBuilder()
                        .video(Video.builder().build()))
                .build();
    }

    private static Imp givenImp(UnaryOperator<Imp.ImpBuilder> impCustomizer) {
        return impCustomizer.apply(Imp.builder()
                        .id("123")
                        .banner(Banner.builder()
                                .id("banner_id")
                                .w(300)
                                .h(500)
                                .build())
                        .ext(mapper.valueToTree(ExtPrebid.of(null,
                                ExtImpSmaato.of("publisherId", "adspaceId", "adbreakId")))))
                .build();
    }

    private static String givenBidResponse(UnaryOperator<Bid.BidBuilder> bidCustomizer) throws
            JsonProcessingException {

        return mapper.writeValueAsString(BidResponse.builder()
                .cur("USD")
                .seatbid(singletonList(SeatBid.builder()
                        .bid(singletonList(bidCustomizer.apply(Bid.builder().impid("123")).build()))
                        .build()))
                .build());
    }

    private static BidderCall<BidRequest> givenHttpCall(BidRequest bidRequest, String body, MultiMap headers) {
        return BidderCall.succeededHttp(
                HttpRequest.<BidRequest>builder().payload(bidRequest).build(),
                HttpResponse.of(200, headers, body),
                null);
    }
}
