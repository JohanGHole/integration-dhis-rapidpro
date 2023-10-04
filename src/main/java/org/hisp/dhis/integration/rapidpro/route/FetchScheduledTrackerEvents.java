package org.hisp.dhis.integration.rapidpro.route;

import io.netty.util.Mapping;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.hisp.dhis.integration.rapidpro.MappingProperties;
import org.hisp.dhis.integration.rapidpro.aggregationStrategy.AttributesAggrStrategy;
import org.hisp.dhis.integration.rapidpro.aggregationStrategy.RapidProContactEnricher;
import org.hisp.dhis.integration.rapidpro.processor.FetchDueEventsQueryParamSetter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class FetchScheduledTrackerEvents extends AbstractRouteBuilder
{
    @Autowired
    protected FetchDueEventsQueryParamSetter fetchDueEventsQueryParamSetter;

    @Autowired
    protected AttributesAggrStrategy attributesAggrStrategy;

    @Autowired
    protected RapidProContactEnricher rapidProContactEnricher;

    @Autowired
    protected MappingProperties mappingProperties;

    @Override
    protected void doConfigure() throws Exception
    {
        from( "servlet:tasks/fetchDueEvents?muteException=true" )
            .precondition( "{{fetch.dhis2.tracker.events}}" )
            .removeHeaders( "*" )
            .to( "direct:flowStarter" )
            .setHeader( Exchange.CONTENT_TYPE, constant( "application/json" ) )
            .setBody( constant( Map.of( "status", "success", "data", "Fetched due program stage events" ) ) )
            .marshal().json();

        from( "quartz://sync?cron={{sync.schedule.expression:0 0/30 * * * ?}}&stateful=true" )
            .precondition( "{{fetch.dhis2.tracker.events}}" )
            .to( "direct:flowStarter" );

        from( "direct:flowStarter" )
            .routeId( "Flow Starter" )
            .to( "direct:fetchDueEvents" )
            .choice().when( simple( "${exchangeProperty.dueEventsCount} > 0" ) )
                .split( simple( "${exchangeProperty.dueEvents}" ) )
                    .parallelProcessing()
                    .to( "direct:fetchTrackedEntityAttributes" )
                    .to("direct:handleContact")
                    .to("direct:triggerRapidproFlow")
                .end()
            .end()
        .end();

        from( "direct:fetchDueEvents" )
            .routeId( "Fetch Due Events" )
            .process( fetchDueEventsQueryParamSetter )
            .toD( "dhis2://get/resource?path=tracker/events&fields=enrollment,programStage,scheduledAt,event,status,trackedEntity&client=#dhis2Client" )
            .unmarshal().json()
            .removeHeader("CamelDhis2.queryParams")
            .setProperty( "dueEventsCount", jsonpath( "$.instances.length()" ) )
            .setProperty( "dueEvents", jsonpath( "$.instances" ) )
            .log( LoggingLevel.DEBUG, LOGGER, "Fetched ${exchangeProperty.dueEventsCount} due events from DHIS2" )
            .end();

        from( "direct:fetchTrackedEntityAttributes" )
            .routeId( "Fetch Tracked Entity Attributes" )
            .enrich().simple( "dhis2://get/resource?path=tracker/enrollments/${body[enrollment]}&fields=attributes[attribute,code,value]&client=#dhis2Client").aggregationStrategy( attributesAggrStrategy )
            .log( LoggingLevel.DEBUG, LOGGER, "New body after attribute enrichment => ${body} with headers ${headers}" )
            .end();

        from("direct:handleContact")
            .routeId("Handle Contact")
            .log( LoggingLevel.INFO, LOGGER, "Synchronising Tracked Entity Instance with RapidPro Contact..." )
            .log(LoggingLevel.DEBUG,LOGGER, "Checking if contact exists for urn: whatsapp:${body[phoneNumber]}")
            .setHeader( "Authorization", constant( "Token {{rapidpro.api.token}}" ) )
            .enrich().simple("{{rapidpro.api.url}}/contacts.json?urn=whatsapp:${body[phoneNumber]}&httpMethod=GET").aggregationStrategy( rapidProContactEnricher )
            .setProperty("originalPayload",simple("${body}"))
            .choice()
            .when(simple("${body[results].size()} > 0"))
                .log(LoggingLevel.DEBUG,LOGGER, "RapidPro Contact with urn: whatsapp:${body[phoneNumber]} already exists. No action needed.")
            .otherwise()
                .log(LoggingLevel.DEBUG, LOGGER, "RapidPro contact does not exist. Creating a new contact..")
                .transform( datasonnet( "resource:classpath:trackedEntityContact.ds", Map.class, "application/x-java-object", "application/x-java-object" ) )
                .setHeader( "Authorization", constant( "Token {{rapidpro.api.token}}" ) )
                .log( LoggingLevel.DEBUG, LOGGER, "Creating RapidPro contact for DHIS2 enrollment ${exchangeProperty.originalPayload[enrollment]} with URN ${body[urns]}" )
                .marshal().json().convertBodyTo( String.class )
                .toD( "{{rapidpro.api.url}}/contacts.json?httpMethod=POST&okStatusCodeRange=200-499" )
                .choice().when( header( Exchange.HTTP_RESPONSE_CODE ).isNotEqualTo( "201" ) )
                    .log( LoggingLevel.WARN, LOGGER, "Unexpected status code when creating RapidPro contact for DHIS2 enrollment ${exchangeProperty.originalPayload[enrollment]} with phoneNumber ${exchangeProperty.originalPayload[phoneNumber]} => HTTP ${header.CamelHttpResponseCode}. HTTP response body => ${body}" )
                .end()
            .end()
            .setBody(simple("${exchangeProperty.originalPayload}"))
            .end();

        from("direct:triggerRapidproFlow")
            .routeId("Trigger RapidPro Flow")
            .process(exchange -> {
                Map<String, Object> currentBody = exchange.getIn().getBody(Map.class);
                String flowId = mappingProperties.getFlowIdByProgramStageId( (String) currentBody.get("programStage") ); // Assuming urns is a List based on the given JSON.
                currentBody.put("flowId", flowId);
                exchange.getIn().setBody(currentBody);
            })
            .log(LoggingLevel.DEBUG, LOGGER, "Body of flow trigger before transformation => ${body}")
            .transform( datasonnet( "resource:classpath:flowStart.ds", Map.class, "application/x-java-object", "application/x-java-object" ) )
            .setHeader( "Authorization", constant( "Token {{rapidpro.api.token}}" ) )
            .log( LoggingLevel.DEBUG, LOGGER, "Triggering flow start with id => $body[flow] for RapidPro contact ${body[urns]}" )
            .marshal().json().convertBodyTo( String.class )
            .toD( "{{rapidpro.api.url}}/flow_starts.json" );

            // perform mapping
            //


    }
}
