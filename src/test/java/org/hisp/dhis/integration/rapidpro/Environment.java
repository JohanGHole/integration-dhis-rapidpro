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

import com.github.javafaker.Faker;
import com.github.javafaker.Name;
import io.restassured.RestAssured;
import io.restassured.builder.MultiPartSpecBuilder;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.hisp.dhis.api.model.v40_0.Entity;
import org.hisp.dhis.api.model.v40_0.JsonPatch;
import org.hisp.dhis.api.model.v40_0.Notification;
import org.hisp.dhis.api.model.v40_0.OrganisationUnit;
import org.hisp.dhis.api.model.v40_0.OrganisationUnitLevel;
import org.hisp.dhis.api.model.v40_0.PersistenceReport;
import org.hisp.dhis.api.model.v40_0.ProgramStage;
import org.hisp.dhis.api.model.v40_0.RefOrganisationUnit;
import org.hisp.dhis.api.model.v40_0.RefUserRole;
import org.hisp.dhis.api.model.v40_0.TrackedEntity;
import org.hisp.dhis.api.model.v40_0.TrackerReportImportReport;
import org.hisp.dhis.api.model.v40_0.TrackerTypeReport;
import org.hisp.dhis.api.model.v40_0.User;
import org.hisp.dhis.api.model.v40_0.UserCredentialsDto;
import org.hisp.dhis.api.model.v40_0.WebMessage;
import org.hisp.dhis.api.model.v40_0.WebapiControllerTrackerViewAttribute;
import org.hisp.dhis.api.model.v40_0.WebapiControllerTrackerViewRelationshipItemEnrollment;
import org.hisp.dhis.api.model.v40_0.WebapiControllerTrackerViewRelationshipItemEvent;
import org.hisp.dhis.integration.sdk.Dhis2ClientBuilder;
import org.hisp.dhis.integration.sdk.api.Dhis2Client;
import org.hisp.dhis.integration.sdk.api.Dhis2Response;
import org.hisp.dhis.integration.sdk.internal.security.BasicCredentialsSecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StreamUtils;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class Environment
{
    public static final String DHIS_IMAGE_NAME = System.getProperty( "dhis.image.name" );

    public static String ORG_UNIT_ID;

    public static Dhis2Client DHIS2_CLIENT;

    public static GenericContainer<?> HTTPBIN_CONTAINER;

    public static GenericContainer<?> DHIS2_CONTAINER;

    public static String RAPIDPRO_API_URL;

    public static String RAPIDPRO_API_TOKEN;

    private static final Logger LOGGER = LoggerFactory.getLogger( Environment.class );

    private static final Network RAPIDPRO_NETWORK = Network.builder().build();

    private static final Network DHIS2_NETWORK = Network.builder().build();

    private static GenericContainer<?> RAPIDPRO_CONTAINER;

    private static GenericContainer<?> REDIS_CONTAINER;

    private static GenericContainer<?> ELASTICSEARCH_CONTAINER;

    private static GenericContainer<?> MAILROOM_CONTAINER;

    public static RequestSpecification RAPIDPRO_REQUEST_SPEC;

    public static RequestSpecification RAPIDPRO_API_REQUEST_SPEC;

    public static PostgreSQLContainer<?> DHIS2_DB_CONTAINER;

    static
    {
        try
        {
            RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

            composeRapidProContainers();
            composeDhis2Containers();
            startContainers();

            setUpRapidPro();
            setUpDhis2();
        }
        catch ( Exception e )
        {
            LOGGER.error( e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    private static void setUpDhis2()
        throws
        IOException,
        InterruptedException,
        ParseException
    {
        String dhis2ApiUrl = String.format( "http://%s:%s/api", DHIS2_CONTAINER.getHost(),
            DHIS2_CONTAINER.getFirstMappedPort() );

        System.setProperty( "dhis2.api.url", dhis2ApiUrl );

        DHIS2_CLIENT = Dhis2ClientBuilder.newClient( dhis2ApiUrl,
                new BasicCredentialsSecurityContext( "admin", "district" ), 5, 300000L, 0L, 20000L, 20000L, 10000L )
            .build();

        ORG_UNIT_ID = createOrgUnit();
        createOrgUnitLevel();
        String orgUnitLevelId = null;
        for ( OrganisationUnitLevel organisationUnitLevel : DHIS2_CLIENT.get( "organisationUnitLevels" )
            .withFields( "id" )
            .withoutPaging().transfer().returnAs( OrganisationUnitLevel.class, "organisationUnitLevels" ) )
        {
            orgUnitLevelId = organisationUnitLevel.getId().get();
        }

        importMetaData( Objects.requireNonNull( orgUnitLevelId ) );
        addOrgUnitToAdminUser( ORG_UNIT_ID );
        addOrgUnitToDataSet( ORG_UNIT_ID );
        addOrgUnitToTrackerProgram( ORG_UNIT_ID );
        createDhis2Users( ORG_UNIT_ID );
        //updateProgramStageConfiguration();
        createDhis2TrackedEntitiesWithEnrollment( ORG_UNIT_ID );
        runAnalytics();
    }

    private static void setUpRapidPro()
        throws
        IOException,
        InterruptedException
    {
        RAPIDPRO_CONTAINER.execInContainer(
            "sh", "-c", "python manage.py createsuperuser --username root --email admin@dhis2.org --noinput" );

        String rapidProBaseUri = String.format( "http://%s:%s", RAPIDPRO_CONTAINER.getHost(),
            RAPIDPRO_CONTAINER.getFirstMappedPort() );

        RAPIDPRO_API_URL = rapidProBaseUri + "/api/v2";

        System.setProperty( "rapidpro.api.url", RAPIDPRO_API_URL );

        RAPIDPRO_REQUEST_SPEC = new RequestSpecBuilder().setBaseUri( rapidProBaseUri ).build();

        given( RAPIDPRO_REQUEST_SPEC ).contentType( ContentType.URLENC ).formParams(
                Map.of( "first_name", "Alice", "last_name", "Wonderland", "email", "claude@dhis2.org", "password",
                    "12345678", "timezone", "Europe/Berlin", "name", "dhis2" ) )
            .when()
            .post( "/org/signup/" ).then().statusCode( 302 );

        String flowDefinition = StreamUtils.copyToString(
            Thread.currentThread().getContextClassLoader().getResourceAsStream( "flow.json" ),
            Charset.defaultCharset() );
        String programStageFlowDefinition = StreamUtils.copyToString(
            Thread.currentThread().getContextClassLoader().getResourceAsStream( "programStageFlow.json" ),
            Charset.defaultCharset() );

        importFlowUnderTest( flowDefinition );
        importFlowUnderTest( programStageFlowDefinition );

        RAPIDPRO_API_TOKEN = generateRapidProApiToken();
        RAPIDPRO_API_REQUEST_SPEC = new RequestSpecBuilder().setBaseUri( RAPIDPRO_API_URL )
            .addHeader( "Authorization", "Token " + RAPIDPRO_API_TOKEN ).build();
        System.setProperty( "rapidpro.api.token", RAPIDPRO_API_TOKEN );
    }

    private Environment()
    {

    }

    private static void importFlowUnderTest(String flowDefinition)
        throws
        IOException
    {

        String sessionId = fetchRapidProSessionId( "claude@dhis2.org", "12345678" );
        ExtractableResponse<Response> flowImportPageResponse = given( RAPIDPRO_REQUEST_SPEC ).cookie( "sessionid",
                sessionId ).
            when().get( "/org/import/" ).then().statusCode( 200 ).extract();
        String flowImportCsrfMiddlewareToken = flowImportPageResponse.htmlPath()
            .getString( "html.body.div.div[4].div.div.div.div[3].form.input.@value" );

        given( RAPIDPRO_REQUEST_SPEC ).cookie( "sessionid", sessionId ).contentType( "multipart/form-data" )
            .multiPart(
                new MultiPartSpecBuilder( flowImportCsrfMiddlewareToken ).emptyFileName().mimeType( "text/plain" )
                    .controlName( "csrfmiddlewaretoken" ).build() )
            .multiPart( new MultiPartSpecBuilder( flowDefinition ).mimeType( "application/json" )
                .header( "Content-Disposition", "form-data; name=\"import_file\"; filename=\"flow.json\"" )
                .header( "Content-Transfer-Encoding", "binary" ).build() )
            .when()
            .post( "/org/import/" ).then().statusCode( 302 ).header( "Location", "/org/home/" );
    }

    private static void startContainers()
    {
        HTTPBIN_CONTAINER = new GenericContainer<>(
            DockerImageName.parse( "kennethreitz/httpbin:latest" ) ).withExposedPorts( 80 );
        Stream.of( HTTPBIN_CONTAINER, REDIS_CONTAINER, ELASTICSEARCH_CONTAINER,
            RAPIDPRO_CONTAINER,
            MAILROOM_CONTAINER, DHIS2_CONTAINER ).parallel().forEach( GenericContainer::start );
    }

    private static void composeDhis2Containers()
    {
        DHIS2_DB_CONTAINER = newPostgreSQLContainer( "dhis2", "dhis", "dhis", DHIS2_NETWORK );

        DHIS2_CONTAINER = new GenericContainer<>(
            String.format( "dhis2/core:%s", DHIS_IMAGE_NAME ) )
            .withClasspathResourceMapping( "dhis.conf", "/DHIS2_home/dhis.conf", BindMode.READ_WRITE )
            .withClasspathResourceMapping( "dhis.conf", "/opt/dhis2/dhis.conf", BindMode.READ_WRITE )
            .withNetwork( DHIS2_NETWORK ).withExposedPorts( 8080 )
            .dependsOn( DHIS2_DB_CONTAINER )
            .waitingFor( new HttpWaitStrategy().forStatusCode( 200 ).withStartupTimeout( Duration.ofMinutes( 5 ) ) )
            .withEnv( "WAIT_FOR_DB_CONTAINER", "db" + ":" + 5432 + " -t 0" );
    }

    private static void composeRapidProContainers()
    {
        PostgreSQLContainer<?> rapidProDbContainer = newPostgreSQLContainer( "temba", "temba", "temba",
            RAPIDPRO_NETWORK );

        REDIS_CONTAINER = new GenericContainer<>(
            DockerImageName.parse( "redis:6.2.6-alpine" ) )
            .withNetworkAliases( "redis" )
            .withExposedPorts( 6379 )
            .withNetwork( RAPIDPRO_NETWORK );

        ELASTICSEARCH_CONTAINER = new GenericContainer<>(
            DockerImageName.parse( "elasticsearch:6.8.23" ) )
            .withEnv( "discovery.type", "single-node" )
            .withNetwork( RAPIDPRO_NETWORK )
            .withNetworkAliases( "elasticsearch" )
            .withExposedPorts( 9200 )
            .waitingFor( new HttpWaitStrategy().forStatusCode( 200 ) );

        ImageFromDockerfile rapidproImage = new ImageFromDockerfile( "rapidpro:7.4.2", false ).withDockerfile(
                Path.of( "rapidpro-docker/rapidpro/Dockerfile" ) ).withBuildArg( "RAPIDPRO_REPO", "rapidpro/rapidpro" )
            .withBuildArg( "RAPIDPRO_VERSION", "v7.4.2" );

        RAPIDPRO_CONTAINER = new GenericContainer<>( rapidproImage )
            .dependsOn( rapidProDbContainer )
            .withExposedPorts( 8000 )
            .withNetwork( RAPIDPRO_NETWORK )
            .waitingFor( new HttpWaitStrategy().forStatusCode( 200 ).withStartupTimeout( Duration.ofMinutes( 5 ) ) )
            .withEnv( "SECRET_KEY", "super-secret-key" )
            .withEnv( "DATABASE_URL", "postgresql://temba:temba@db/temba" )
            .withEnv( "REDIS_URL", "redis://redis:6379/0" )
            .withEnv( "DJANGO_DEBUG", "on" )
            .withEnv( "DOMAIN_NAME", "localhost" )
            .withEnv( "MANAGEPY_COLLECTSTATIC", "on" )
            .withEnv( "MANAGEPY_INIT_DB", "on" )
            .withEnv( "MANAGEPY_MIGRATE", "on" )
            .withEnv( "DJANGO_SUPERUSER_PASSWORD", "12345678" )
            .withEnv( "MAILROOM_URL", "http://mailroom:8090" )
            .withEnv( "MAILROOM_AUTH_TOKEN", "Gqcqvi2PGkoZMpQi" )
            .withEnv( "ELASTICSEARCH_URL", "http://elasticsearch:9200" )
            .withCommand( "sh", "-c",
                "sed -i '/CsrfViewMiddleware/s/^/#/g' temba/settings_common.py && /startup.sh" );

        ImageFromDockerfile mailroomImage = new ImageFromDockerfile( "mailroom:7.4.1", false ).withDockerfile(
                Path.of( "rapidpro-docker/mailroom/Dockerfile" ) ).withBuildArg( "MAILROOM_REPO", "rapidpro/mailroom" )
            .withBuildArg( "MAILROOM_VERSION", "7.4.1" );

        MAILROOM_CONTAINER = new GenericContainer<>(
            mailroomImage )
            .withNetwork( RAPIDPRO_NETWORK )
            .withExposedPorts( 8090 )
            .withNetworkAliases( "mailroom" )
            .withEnv( "MAILROOM_DOMAIN", "mailroom" )
            .withEnv( "MAILROOM_ELASTIC", "http://elasticsearch:9200" )
            .withEnv( "MAILROOM_ATTACHMENT_DOMAIN", "mailroom" )
            .withEnv( "MAILROOM_AUTH_TOKEN", "Gqcqvi2PGkoZMpQi" )
            .withEnv( "MAILROOM_DB", "postgres://temba:temba@db/temba?sslmode=disable" )
            .withEnv( "MAILROOM_REDIS", "redis://redis:6379/0" )
            .withCommand( "mailroom", "--address", "0.0.0.0" );
    }

    private static PostgreSQLContainer<?> newPostgreSQLContainer( String databaseName,
        String username, String password, Network network )
    {
        return new PostgreSQLContainer<>(
            DockerImageName.parse( "postgis/postgis:12-3.2-alpine" ).asCompatibleSubstituteFor( "postgres" ) )
            .withDatabaseName( databaseName )
            .withNetworkAliases( "db" )
            .withUsername( username )
            .withPassword( password ).withNetwork( network );
    }

    public static void runAnalytics()
        throws
        InterruptedException,
        IOException
    {
        DHIS2_CLIENT.post( "maintenance" ).withParameter( "cacheClear", "true" )
            .transfer().close();

        WebMessage webMessage = DHIS2_CLIENT.post( "resourceTables/analytics" ).transfer()
            .returnAs( WebMessage.class );
        String taskId = webMessage.getResponse().get().get( "id" );

        Notification notification = null;
        while ( notification == null || !(Boolean) notification.getCompleted() )
        {
            Thread.sleep( 5000 );
            Iterable<Notification> notifications = DHIS2_CLIENT.get( "system/tasks/ANALYTICS_TABLE/{taskId}",
                taskId ).withoutPaging().transfer().returnAs( Notification.class );
            if ( notifications.iterator().hasNext() )
            {
                notification = notifications.iterator().next();
            }
        }
    }

    private static void addOrgUnitToAdminUser( String orgUnitId )
        throws
        IOException
    {
        DHIS2_CLIENT.post( "users/M5zQapPyTZI/organisationUnits/{organisationUnitId}", orgUnitId ).transfer().close();
    }

    private static void addOrgUnitToDataSet( String orgUnitId )
        throws
        IOException
    {
        DHIS2_CLIENT.post( "dataSets/qNtxTrp56wV/organisationUnits/{orgUnitId}", orgUnitId )
            .transfer()
            .close();

        DHIS2_CLIENT.post( "dataSets/VEM58nY22sO/organisationUnits/{orgUnitId}", orgUnitId )
            .transfer()
            .close();
    }

    private static void addOrgUnitToTrackerProgram( String orgUnitId )
        throws
        IOException
    {
        DHIS2_CLIENT.post( "programs/w0qPtIW0JYu/organisationUnits/{orgUnitId}", orgUnitId )
            .transfer()
            .close();
    }

    public static void createDhis2Users( String orgUnitId )
    {
        int phoneNumber = 21000000;
        for ( int i = 0; i < 10; i++ )
        {
            createDhis2User( orgUnitId, "00356" + phoneNumber );
            phoneNumber++;
        }
    }

    public static void deleteDhis2Users()
        throws
        IOException
    {
        Iterable<User> usersIterable = DHIS2_CLIENT.get( "users" ).withFilter( "phoneNumber:!null" )
            .withFields( "*" ).withoutPaging()
            .transfer()
            .returnAs( User.class, "users" );

        for ( User user : usersIterable )
        {
            DHIS2_CLIENT.delete( "users/{id}", user.getId().get() ).transfer().close();
        }
    }

    public static String createDhis2User( String orgUnitId, String phoneNumber )
    {
        Faker faker = new Faker();
        Name name = faker.name();

        Dhis2Response dhis2Response = DHIS2_CLIENT.post( "users" )
            .withResource( new User().withFirstName( name.firstName() ).withSurname( name.lastName() )
                .withPhoneNumber( phoneNumber )
                .withAttributeValues( Collections.emptyList() )
                .withOrganisationUnits( List.of( new RefOrganisationUnit().withId( orgUnitId ) ) )
                .withUserCredentials(
                    new UserCredentialsDto().withCatDimensionConstraints( Collections.emptyList() )
                        .withCogsDimensionConstraints( Collections.emptyList() ).withUsername( name.username() )
                        .withPassword( "aKa9CD8HyAi8Y7!" ).withUserRoles(
                            List.of( new RefUserRole().withId( "yrB6vc5Ip3r" ) ) ) ) )
            .transfer();

        return dhis2Response.returnAs( WebMessage.class ).getResponse().get().get( "uid" );
    }

    public static String createDhis2TrackedEntityWithEnrollment( String orgUnitId, String phoneNumber, String patientUID, String firstName )
        throws IOException, ParseException
    {
        final long DAY_IN_MILLISECONDS = 86400000L;
        Faker faker = new Faker();
        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String tomorrow = new SimpleDateFormat("yyyy-MM-dd").format(new Date(new Date().getTime() + DAY_IN_MILLISECONDS) );
        String lastName = faker.name().lastName();
        Date dateOfBirth = faker.date().past( 365 * 60, TimeUnit.DAYS );
        SimpleDateFormat dmyFormat = new SimpleDateFormat( "yyyy-MM-dd" );
        String dateOfBirthFmt = dmyFormat.format( dateOfBirth );
        String gender = "Male";
        TrackedEntity trackedEntity = new TrackedEntity()
            .withOrgUnit( orgUnitId )
            .withTrackedEntityType( "MCPQUTHX1Ze" )
            .withEnrollments(
                List.of(
                    new WebapiControllerTrackerViewRelationshipItemEnrollment()
                        .withOrgUnit( ORG_UNIT_ID )
                        .withProgram( "w0qPtIW0JYu" )
                        .withEnrolledAt( today )
                        .withAttributes(
                            List.of(
                                new WebapiControllerTrackerViewAttribute().withAttribute( "sB1IHYu2xQT" ).withValue( firstName ),
                                new WebapiControllerTrackerViewAttribute().withAttribute( "ENRjVGxVL6l" ).withValue( lastName ),
                                new WebapiControllerTrackerViewAttribute().withAttribute( "HlKXyR5qr2e" ).withValue( patientUID ),
                                new WebapiControllerTrackerViewAttribute().withAttribute( "fctSQp5nAYl" ).withValue( phoneNumber ),
                                new WebapiControllerTrackerViewAttribute().withAttribute( "oindugucx72" ).withValue( gender ),
                                new WebapiControllerTrackerViewAttribute().withAttribute( "NI0QRzJvQ0k" ).withValue( dateOfBirthFmt )
                            ) )
                        .withOccurredAt( today )
                        .withStatus( WebapiControllerTrackerViewRelationshipItemEnrollment.Status.ACTIVE )
                        .withEvents(
                            List.of(
                                new WebapiControllerTrackerViewRelationshipItemEvent()
                                    .withProgramStage( "xT4NoUJyspv" )
                                    .withOrgUnit( ORG_UNIT_ID )
                                    .withScheduledAt( today )
                                    .withOccurredAt( today )
                                    .withProgram( "w0qPtIW0JYu" )
                                    .withStatus( WebapiControllerTrackerViewRelationshipItemEvent.Status.ACTIVE ),
                                new WebapiControllerTrackerViewRelationshipItemEvent()
                                    .withProgramStage( "ZP5HZ87wzc0" )
                                    .withOrgUnit( ORG_UNIT_ID )
                                    .withScheduledAt( today )
                                    .withProgram( "w0qPtIW0JYu" )
                                    .withStatus( WebapiControllerTrackerViewRelationshipItemEvent.Status.SCHEDULE ),
                                new WebapiControllerTrackerViewRelationshipItemEvent()
                                    .withProgramStage( "bTOU9xE67NJ" )
                                    .withOrgUnit( ORG_UNIT_ID )
                                    .withScheduledAt( tomorrow )
                                    .withProgram( "w0qPtIW0JYu" )
                                    .withStatus( WebapiControllerTrackerViewRelationshipItemEvent.Status.SCHEDULE ) ) ) ) );

        String enrollmentId = DHIS2_CLIENT.post( "tracker" )
            .withResource( Map.of( "trackedEntities", List.of( trackedEntity ) ) )
            .withParameter( "async", "false" )
            .transfer()
            .returnAs( TrackerReportImportReport.class ).getBundleReport().get().getTypeReportMap().get()
            .getAdditionalProperties().get( "ENROLLMENT" ).getObjectReports().get().get( 0 ).getUid().get().toString();
        return enrollmentId;
    }

    public static void createDhis2TrackedEntitiesWithEnrollment( String orgUnitId )
        throws IOException, ParseException
    {
        int phoneNumber = 50100;
        int patientId = 1000000;
        for ( int i = 0; i < 10; i++ )
        {
            String firstName = new Faker().name().firstName();
            createDhis2TrackedEntityWithEnrollment( orgUnitId, "55" + phoneNumber, "ID-" + patientId,firstName );
            phoneNumber++;
            patientId++;
        }
    }

    public static void updateProgramStageConfiguration()
    {
        final String[] programStageIds = {"ZP5HZ87wzc0", "bTOU9xE67NJ"};
        for ( String programStageId : programStageIds )
        {
            ProgramStage programStage = DHIS2_CLIENT.get( "programStages/{programStageId}", programStageId ).transfer().returnAs( ProgramStage.class )
                .withDisplayGenerateEventBox( true )
                .withAutoGenerateEvent( true )
                .withHideDueDate( false )
                .withMinDaysFromStart( 0 )
                .withBlockEntryForm( false )
                .withOpenAfterEnrollment( false )
                .withGeneratedByEnrollmentDate( true );
            Dhis2Response dhis2Response = DHIS2_CLIENT.put( "programStages/{programStageId}", programStageId )
                .withResource( programStage )
                .transfer();
            assertEquals( WebMessage.Status.OK, dhis2Response.returnAs( WebMessage.class ).getStatus() );
        }
    }

    public static void deleteDhis2TrackedEntities( String orgUnitId )
        throws
        IOException
    {
        Iterable<TrackedEntity> trackedEntitiesIterable = DHIS2_CLIENT.get( "tracker/trackedEntities" )
            .withoutPaging()
            .withParameter( "program", "w0qPtIW0JYu" )
            .withParameter( "orgUnit", orgUnitId )
            .transfer()
            .returnAs( TrackedEntity.class, "instances" );

        for ( TrackedEntity trackedEntity : trackedEntitiesIterable )
        {
            DHIS2_CLIENT.post( "tracker")
                .withResource( Map.of("trackedEntities",List.of(trackedEntity)) )
                .withParameter("importStrategy","DELETE"  )
                .transfer()
                .close();
        }
    }

    private static String fetchRapidProSessionId( String username, String password )
    {
        ExtractableResponse<Response> loginPageResponse = given( RAPIDPRO_REQUEST_SPEC ).when()
            .get( "/users/login/" ).then().statusCode( 200 ).extract();

        String csrfMiddlewareToken = loginPageResponse.htmlPath()
            .getString( "html.body.div.div[3].div.div.div[1].div.div.form.input.@value" );
        String csrfToken = loginPageResponse.cookie( "csrftoken" );

        return given( RAPIDPRO_REQUEST_SPEC ).contentType( ContentType.URLENC )
            .cookie( "csrftoken", csrfToken )
            .formParams( Map.of( "csrfmiddlewaretoken", csrfMiddlewareToken,
                "username", username, "password", password ) )
            .when()
            .post( "/users/login/" ).then().statusCode( 302 ).extract().cookie( "sessionid" );
    }

    private static String generateRapidProApiToken()
    {
        String sessionId = fetchRapidProSessionId( "root", "12345678" );

        return given( RAPIDPRO_REQUEST_SPEC )
            .cookie( "sessionid", sessionId ).when()
            .post( "/api/apitoken/refresh/" ).then().statusCode( 200 ).extract().path( "token" );
    }

    private static void importMetaData( String orgUnitLevelId )
        throws
        IOException
    {
        String mlagMetaData = StreamUtils.copyToString(
                Thread.currentThread().getContextClassLoader().getResourceAsStream( "MLAG00_1.2.1_DHIS2.36.json" ),
                Charset.defaultCharset() ).replaceAll( "<OU_LEVEL_DISTRICT_UID>", orgUnitLevelId )
            .replaceAll( "<OU_LEVEL_FACILITY_UID>", orgUnitLevelId );
        String afiMetaData = StreamUtils.copyToString(
            Thread.currentThread().getContextClassLoader().getResourceAsStream( "IDS_AFI_COMPLETE_1.0.0_DHIS2.38.json" ),
            Charset.defaultCharset() );
        WebMessage webMessageMlag = DHIS2_CLIENT.post( "metadata" )
            .withResource( mlagMetaData )
            .withParameter( "atomicMode", "NONE" ).transfer().returnAs( WebMessage.class );
        WebMessage webMessageAfi = DHIS2_CLIENT.post( "metadata" )
            .withResource( afiMetaData )
            .withParameter( "atomicMode", "NONE" ).transfer().returnAs( WebMessage.class );
        assertEquals( WebMessage.Status.OK, webMessageMlag.getStatus() );
        assertEquals( WebMessage.Status.OK, webMessageAfi.getStatus() );
    }

    private static void createOrgUnitLevel()
        throws
        IOException
    {
        DHIS2_CLIENT.post( "filledOrganisationUnitLevels" )
            .withResource( Map.of( "organisationUnitLevels",
                List.of( new OrganisationUnitLevel().withName( "Level 1" ).withLevel( 1 ) ) ) )
            .transfer().close();
    }

    private static String createOrgUnit()
    {
        return DHIS2_CLIENT.post( "organisationUnits" ).withResource(
                new OrganisationUnit().withName( "Acme" ).withCode( "ACME" ).withShortName( "Acme" )
                    .withOpeningDate( new Date( 964866178L ) ) ).transfer()
            .returnAs( WebMessage.class ).getResponse().get().get( "uid" );
    }

}
