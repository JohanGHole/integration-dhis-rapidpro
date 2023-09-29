package org.hisp.dhis.integration.rapidpro.route;

import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.LoggingLevel;
import org.hisp.dhis.integration.rapidpro.processor.FetchDueEventsQueryParamSetter;
import org.hisp.dhis.integration.rapidpro.processor.FilterTrackedEntityAttributes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class FetchScheduledTrackerEvents extends AbstractRouteBuilder
{
    @Autowired
    protected FilterTrackedEntityAttributes filterTrackedEntityAttributes;

    @Autowired
    protected FetchDueEventsQueryParamSetter fetchDueEventsQueryParamSetter;

    @Override
    protected void doConfigure() throws Exception
    {
        from( "servlet:tasks/fetchDueEvents?muteException=true" )
            .precondition( "{{fetch.dhis2.tracker.events}}" )
            .removeHeaders( "*" )
            .to( "direct:fetchDueEvents" )
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
                    .setProperty( "eventId", jsonpath( "$.event" ) )
                    .to( "direct:fetchTrackedEntityAttributes" )
                    .log( LoggingLevel.DEBUG, LOGGER, "Fetched and filtered tracked entity attributes: Phone Number: ${exchangeProperty.phoneNumber}," +
                                                              " Given Name: ${exchangeProperty.givenName}" )
                    .to("direct:handleContact")
                    //.to("direct:triggerRapidproFlow")
                .end()
            .end()
        .end();

        from( "direct:fetchDueEvents" )
            .routeId( "Fetch Due Events" )
            .process( fetchDueEventsQueryParamSetter )
            .toD( "dhis2://get/resource?path=tracker/events&fields=enrollment,scheduledAt,event,status,trackedEntity&client=#dhis2Client" )
            .removeHeader("CamelDhis2.queryParams")
            .setProperty( "dueEventsCount", jsonpath( "$.instances.length()" ) )
            .setProperty( "dueEvents", jsonpath( "$.instances" ) )
            .log( LoggingLevel.DEBUG, LOGGER, "Fetched ${exchangeProperty.dueEventsCount} due events from DHIS2" )
            .end();

        from( "direct:fetchTrackedEntityAttributes" )
            .routeId( "Fetch Tracked Entity Attributes" )
            .setProperty( "enrollmentId", jsonpath( "$.enrollment" ) )
            .log(LoggingLevel.DEBUG, LOGGER,"List of exchange properties: ${exchangeProperty.enrollmentId}")
            .toD( "dhis2://get/resource?path=tracker/enrollments/${exchangeProperty.enrollmentId}&fields=attributes[attribute,code,value]&client=#dhis2Client" )
            .log( LoggingLevel.DEBUG, LOGGER, "Fetched enrollment attributes from dhis2 => ${body} with headers ${headers}" )
            .process( filterTrackedEntityAttributes )
            .end();

        from("direct:handleContact")
            .routeId("Handle Contact")
            .log( LoggingLevel.INFO, LOGGER, "Synchronising Tracked Entity Instance with RapidPro Contact..." )
            .log(LoggingLevel.DEBUG,LOGGER, "Checking if contact exists for urn: whatsapp:${exchangeProperty.phoneNumber}")
            .setHeader( "Authorization", constant( "Token {{rapidpro.api.token}}" ) )
            .toD("{{rapidpro.api.url}}/contacts.json?urn=whatsapp:${exchangeProperty.phoneNumber}&httpMethod=GET").unmarshal().json()
            .choice()
            .when(simple("${body[results].size()} > 0"))
                .log(LoggingLevel.DEBUG,LOGGER, "RapidPro Contact with urn: whatsapp:${exchangeProperty.phoneNumber} already exists. No action needed.")
            .otherwise()
                .log(LoggingLevel.DEBUG, LOGGER, "RapidPro contact does not exist. Creating a new contact..")
                .transform( datasonnet( "resource:classpath:trackedEntityContact.ds", Map.class, "application/x-java-object", "application/x-java-object" ) )
                .marshal().json().convertBodyTo( String.class )
                .setHeader( "Authorization", constant( "Token {{rapidpro.api.token}}" ) )
                .log( LoggingLevel.DEBUG, LOGGER, "Creating RapidPro contact for DHIS2 TEI with Id ${exchangeProperty.trackedEntityId}" )
                .toD( "{{rapidpro.api.url}}/contacts.json?httpMethod=POST&okStatusCodeRange=200-499" )
                .choice().when( header( Exchange.HTTP_RESPONSE_CODE ).isNotEqualTo( "201" ) )
                    .log( LoggingLevel.WARN, LOGGER, "Unexpected status code when creating RapidPro contact for DHIS2 TEI with Id ${exchangeProperty.trackedEntityId} => HTTP ${header.CamelHttpResponseCode}. HTTP response body => ${body}" )
                .end();

    }
}
