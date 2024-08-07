package org.prebid.server.it;

import io.restassured.response.Response;
import org.json.JSONException;
import org.junit.jupiter.api.Test;
import org.prebid.server.model.Endpoint;

import java.io.IOException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.Collections.singletonList;

public class ColossusTest extends IntegrationTest {

    @Test
    public void openrtb2AuctionShouldRespondWithBidsFromColossus() throws IOException, JSONException {
        // given
        WIRE_MOCK_RULE.stubFor(post(urlPathEqualTo("/colossus-exchange"))
                .withRequestBody(equalToJson(jsonFrom("openrtb2/colossus/test-colossus-bid-request.json")))
                .willReturn(aResponse().withBody(jsonFrom("openrtb2/colossus/test-colossus-bid-response.json"))));

        // when
        final Response response = responseFor("openrtb2/colossus/test-auction-colossus-request.json",
                Endpoint.openrtb2_auction);

        // then
        assertJsonEquals("openrtb2/colossus/test-auction-colossus-response.json", response,
                singletonList("colossus"));
    }
}
