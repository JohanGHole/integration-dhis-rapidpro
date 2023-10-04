package org.hisp.dhis.integration.rapidpro;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import static io.restassured.RestAssured.given;
import static org.hisp.dhis.integration.rapidpro.Environment.RAPIDPRO_API_REQUEST_SPEC;

public class CustomContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext>
{
    // For injecting program stage mapping before bean initialization
    @Override
    public void initialize( ConfigurableApplicationContext applicationContext )
    {
        String flowUuid = getFlowUuid();
        TestPropertyValues.of(
            "mapping.programStageToFlow.ZP5HZ87wzc0=" + flowUuid
        ).applyTo( applicationContext );
    }

    private String getFlowUuid()
    {
        return given(RAPIDPRO_API_REQUEST_SPEC)
            .get("flows.json")
            .then()
            .extract()
            .path("results.find { it.name == 'Specimen collection and shipment to NRL' }.uuid");
    }
}
