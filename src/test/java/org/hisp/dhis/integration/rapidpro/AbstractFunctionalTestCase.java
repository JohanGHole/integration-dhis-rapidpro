/*
 * Copyright (c) 2004-2022, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.hisp.dhis.integration.rapidpro;

import io.restassured.specification.RequestSpecification;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.apache.camel.test.spring.junit5.UseAdviceWith;
import org.apache.commons.io.FileUtils;
import org.hisp.dhis.api.model.v40_0.DataValue;
import org.hisp.dhis.api.model.v40_0.DataValueSet;
import org.hisp.dhis.integration.sdk.support.period.PeriodBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hisp.dhis.integration.rapidpro.Environment.DHIS2_CLIENT;

@SpringBootTest( webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT )
@CamelSpringBootTest
@UseAdviceWith
@ActiveProfiles( "test" )
@DirtiesContext( classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD )
public class AbstractFunctionalTestCase
{
    protected static RequestSpecification RAPIDPRO_API_REQUEST_SPEC;

    @Autowired
    protected CamelContext camelContext;

    @Autowired
    protected ProducerTemplate producerTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @LocalServerPort
    protected int serverPort;

    protected String dhis2RapidProHttpEndpointUri;

    @BeforeAll
    public static void beforeAll()
    {
        RAPIDPRO_API_REQUEST_SPEC = Environment.RAPIDPRO_API_REQUEST_SPEC;
        if ( System.getProperties().get( "spring.sql.init.platform" ).equals( "postgresql" ) )
        {
            System.setProperty( "spring.datasource.url", String.format( "jdbc:postgresql://localhost:%s/dhis2",
                Environment.DHIS2_DB_CONTAINER.getFirstMappedPort() ) );
            System.setProperty( "spring.datasource.username", "dhis" );
            System.setProperty( "spring.datasource.password", "dhis" );
            System.setProperty( "spring.datasource.driver-class-name", "org.postgresql.Driver" );
        }
    }

    @BeforeEach
    public void beforeEach()
        throws
        Exception
    {
        FileUtils.deleteDirectory( new File( "target/routes" ) );

        System.clearProperty( "sync.rapidpro.contacts" );
        System.clearProperty( "org.unit.id.scheme" );
        System.clearProperty( "reminder.data.set.codes" );
        System.clearProperty( "report.delivery.schedule.expression" );
        System.clearProperty( "rapidpro.flow.uuids" );
        System.clearProperty( "rapidpro.webhook.enabled" );

        jdbcTemplate.execute( "TRUNCATE TABLE DEAD_LETTER_CHANNEL" );
        jdbcTemplate.execute( "TRUNCATE TABLE SUCCESS_LOG" );
        jdbcTemplate.execute( "TRUNCATE TABLE MESSAGES" );

        for ( Map<String, Object> contact : fetchRapidProContacts() )
        {
            given( RAPIDPRO_API_REQUEST_SPEC ).delete( "/contacts.json?uuid={uuid}",
                    contact.get( "uuid" ) )
                .then()
                .statusCode( 204 );
        }

        dhis2RapidProHttpEndpointUri = String.format( "http://0.0.0.0:%s/dhis2rapidpro",
            serverPort );

        DHIS2_CLIENT.post( "dataValueSets" ).withResource(
                new DataValueSet().withCompleteDate(
                        ZonedDateTime.now( ZoneOffset.UTC ).format( DateTimeFormatter.ISO_INSTANT ) )
                    .withOrgUnit( Environment.ORG_UNIT_ID )
                    .withDataSet( "qNtxTrp56wV" ).withPeriod( PeriodBuilder.yearOf( new Date(), -1 ) )
                    .withDataValues(
                        List.of(
                            new DataValue().withDataElement( "MAL_POP_TOTAL" ).withCategoryOptionCombo( "MAL-0514Y" )
                                .withValue( "0" ),
                            new DataValue().withDataElement( "MAL_LLIN_DISTR_PW" ).withValue( "0" ),
                            new DataValue().withDataElement( "GEN_DOMESTIC_FUND" ).withValue( "0" ),
                            new DataValue().withDataElement( "GEN_EXT_FUND" ).withValue( "0" ) ) ) )
            .withParameter( "dataElementIdScheme", "CODE" )
            .withParameter( "categoryOptionComboIdScheme", "CODE" )
            .transfer().close();

        doBeforeEach();
    }

    public void doBeforeEach()
        throws
        Exception
    {
    }

    @AfterEach
    public void afterEach()
        throws
        IOException
    {
        FileUtils.deleteDirectory( new File( "target/routes" ) );
    }

    protected List<Map<String, Object>> fetchRapidProContacts()
    {
        Map<String, Object> contacts = given( RAPIDPRO_API_REQUEST_SPEC ).get( "/contacts.json" ).then()
            .statusCode( 200 ).extract()
            .body().as(
                Map.class );
        return (List<Map<String, Object>>) contacts.get( "results" );
    }

    protected String syncContactsAndFetchFirstContactUuid()
    {
        producerTemplate.sendBody( "direct:sync", null );

        return given( RAPIDPRO_API_REQUEST_SPEC ).get( "/contacts.json?group=DHIS2" )
            .then().extract().path( "results[0].uuid" );
    }
}
