package org.hisp.dhis.integration.rapidpro.route;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.PropertyInject;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.direct.DirectConsumerNotAvailableException;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.spi.CamelLogger;
import org.apache.camel.spring.boot.SpringBootCamelContext;
import org.hisp.dhis.api.model.v40_0.User;
import org.hisp.dhis.api.model.v40_0.WebMessage;
import org.hisp.dhis.integration.rapidpro.AbstractFunctionalTestCase;
import org.hisp.dhis.integration.rapidpro.CustomContextInitializer;
import org.hisp.dhis.integration.rapidpro.Environment;
import org.hisp.dhis.integration.rapidpro.MappingProperties;
import org.hisp.dhis.integration.rapidpro.SelfSignedHttpClientConfigurer;
import org.hisp.dhis.integration.rapidpro.processor.FetchDueEventsQueryParamSetter;
import org.junit.Before;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.PropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static io.restassured.path.json.JsonPath.from;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestPropertySource(properties = {"dhis2.phone.number.attribute.uid=fctSQp5nAYl", "dhis2.program.id=w0qPtIW0JYu",
    "dhis2.given.name.attribute.uid=sB1IHYu2xQT"})
@ContextConfiguration(initializers = CustomContextInitializer.class)
public class FetchScheduledTrackerEventsTestCase extends AbstractFunctionalTestCase
{
    @Autowired
    protected ObjectMapper objectMapper;

    protected static final Logger LOGGER = LoggerFactory.getLogger( FetchScheduledTrackerEventsTestCase.class );


    @Override
    public void doBeforeEach()
        throws
        IOException, ParseException
    {
        Environment.deleteDhis2TrackedEntities(Environment.ORG_UNIT_ID);
        Environment.createDhis2TrackedEntitiesWithEnrollment( Environment.ORG_UNIT_ID );
    }

    @Test
    public void testFetchDueEventsReturnsExpectedNumber() throws Exception {
        AdviceWith.adviceWith( camelContext, "Fetch Due Events", r -> r.weaveAddLast().to( "mock:spy" ) );
        MockEndpoint spyEndpoint = camelContext.getEndpoint( "mock:spy", MockEndpoint.class );
        spyEndpoint.expectedMessageCount(1);

        camelContext.start();
        producerTemplate.sendBody( "direct:fetchDueEvents", ExchangePattern.InOnly, null );
        Thread.sleep( 5000 );
        Exchange exchange = spyEndpoint.getExchanges().get(0);
        int dueEventsCount = exchange.getProperty( "dueEventsCount",Integer.class );
        assertEquals( 10, dueEventsCount );
    }
    @Test
    public void testFetchTrackedEntityAttributesAndFilterAttributes() throws Exception {
        String phoneNumber = "1234";
        String givenName = "John";
        String enrollmentId = Environment.createDhis2TrackedEntityWithEnrollment( Environment.ORG_UNIT_ID, phoneNumber,"ABC-123",givenName );
        AdviceWith.adviceWith( camelContext, "Fetch Tracked Entity Attributes", r -> r.weaveAddLast().to("mock:spy") );
        MockEndpoint spyEndpoint = camelContext.getEndpoint ( "mock:spy",MockEndpoint.class);
        Thread.sleep(1000);
        camelContext.start();
        Map<String, Object> body = new HashMap<>();
        body.put("enrollment", enrollmentId);
        producerTemplate.sendBody("direct:fetchTrackedEntityAttributes",ExchangePattern.InOut, body);
        Thread.sleep(1000);
        Map<String,Object> spyBody = spyEndpoint.getExchanges().get(0).getMessage().getBody(Map.class);
        assertEquals( phoneNumber,spyBody.get("phoneNumber"));
        assertEquals( givenName,spyBody.get( "givenName" ) );
    }
    @Test
    public void testFlowStarter() throws Exception {
        AdviceWith.adviceWith( camelContext, "Flow Starter", r -> r.weaveAddLast().to("mock:spy") );
        MockEndpoint spyEndpoint = camelContext.getEndpoint ( "mock:spy",MockEndpoint.class);
        spyEndpoint.expectedMessageCount( 1 );
        camelContext.start();
        producerTemplate.sendBody("direct:flowStarter",ExchangePattern.InOnly,null);
        Thread.sleep(1000);
        spyEndpoint.assertIsSatisfied();

    }
    @Test
    public void testContactCreationGivenValidUrn() throws IOException
    {
        Environment.deleteDhis2TrackedEntities( Environment.ORG_UNIT_ID );
        camelContext.start();
        Map<String,Object> body = new HashMap<>();
        body.put( "phoneNumber","12345678" );
        producerTemplate.sendBody( "direct:handleContact", body);
        given( RAPIDPRO_API_REQUEST_SPEC ).get( "contacts.json" ).then()
            .body( "results.size()", equalTo( 1 ) )
            .body( "results[0].urns[0]", equalTo( "whatsapp:12345678" ) );
    }
    // TODO: Test end-to-end (10 contacts created), test existing user case
    @Test
    public void testContactCreationGivenInvalidUrn() throws Exception
    {
        assertPreCondition();
        CountDownLatch expectedLogMessage = new CountDownLatch( 2 );
        ((SpringBootCamelContext) camelContext)
            .addLogListener( ( Exchange exchange, CamelLogger camelLogger, String message ) -> {
                if ( camelLogger.getLevel().name().equals( "WARN" ) && message.startsWith(
                    "Unexpected status code when creating RapidPro contact for " ) )
                {
                    expectedLogMessage.countDown();
                }
                return message;
            } );
        camelContext.start();
        Map<String,Object> body = new HashMap<>();
        body.put( "phoneNumber","invalid" );
        producerTemplate.sendBody( "direct:handleContact", body);
        assertEquals( 1, expectedLogMessage.getCount() );
        assertPreCondition();
    }

    @Test
    public void testNoContactCreationWhenContactAlreadyExists()
    {
        assertPreCondition();
        CountDownLatch expectedLogMessage = new CountDownLatch( 2 );
        ((SpringBootCamelContext) camelContext)
            .addLogListener( ( Exchange exchange, CamelLogger camelLogger, String message ) -> {
                if ( camelLogger.getLevel().name().equals( "DEBUG" ) && message.startsWith(
                    "RapidPro Contact with urn: " ) )
                {
                    expectedLogMessage.countDown();
                }
                return message;
            } );
        camelContext.start();
        Map<String,Object> body = new HashMap<>();
        body.put( "phoneNumber","12345678" );
        producerTemplate.sendBody( "direct:handleContact", body);
        assertEquals( 2, expectedLogMessage.getCount() );
        producerTemplate.sendBody( "direct:handleContact", body);
        assertEquals( 1, expectedLogMessage.getCount() );
        given(RAPIDPRO_API_REQUEST_SPEC).get("contacts.json").then()
            .body("results.size()",equalTo( 1 ));
    }
    @Test
    public void testEndToEnd() throws Exception
    {
        assertPreCondition();
        camelContext.start();
        LOGGER.debug("FLOW UUID: "+ new MappingProperties().getFlowIdByProgramStageId( "ZP5HZ87wzc0" ) );
        producerTemplate.sendBody( "direct:flowStarter", ExchangePattern.InOnly, null );
        Thread.sleep( 1000 );
        assertPostCondition();
        given (RAPIDPRO_API_REQUEST_SPEC ).get("flow_starts.json").then()
            .body("results.size()",equalTo(10));
    }
    //@Test
    public void testRapidProFlow() throws Exception
    {

    }
    private void assertPreCondition()
    {
        given( RAPIDPRO_API_REQUEST_SPEC ).get( "contacts.json" ).then()
            .body( "results.size()", equalTo( 0 ) );
    }

    private void assertPostCondition() {
        given (RAPIDPRO_API_REQUEST_SPEC ).get("contacts.json").then()
            .body("results.size()",equalTo(10));
    }
}


