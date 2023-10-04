package org.hisp.dhis.integration.rapidpro;

import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.apache.camel.test.spring.junit5.UseAdviceWith;
import org.hisp.dhis.integration.rapidpro.aggregationStrategy.AttributesAggrStrategy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.path.json.JsonPath.from;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = {
    "mapping.programStageToFlow.abc=123", "mapping.programStageToFlow.def=456"
})
public class ProgramStageToFlowMappingTestCase extends AbstractFunctionalTestCase
{
    @Autowired
    private MappingProperties mappingProperties;

    protected static final Logger LOGGER = LoggerFactory.getLogger( ProgramStageToFlowMappingTestCase.class );

    @BeforeEach
    public void beforeEach() {
        System.setProperty( "mapping.programStageToFlow.ZP5HZ87wzc0",getFlowUuid());
    }
    @Test
    public void testProgramStageToFlowMapping() {
        camelContext.start();
        assertNotNull(mappingProperties.getProgramStageToFlow());
        assertEquals( "123",mappingProperties.getFlowIdByProgramStageId("abc") );
    }
    @Test
    public void testFlowToProgramStageMapping() {
        camelContext.start();
        Map<String, String> programStageToFlowMap = mappingProperties.getProgramStageToFlow();
        assertNotNull(programStageToFlowMap);
        assertEquals( "def",mappingProperties.getProgramStageIdByFlowId("456") );
    }
    @Test
    public void testFlowIdExistWithValidId() {
        camelContext.start();
        Map<String, String> programStageToFlowMap = mappingProperties.getProgramStageToFlow();
        assertNotNull(programStageToFlowMap);
        assertEquals( true,mappingProperties.flowIdExists("123") );
    }
    @Test
    public void testGetFlowUuid() {
        String uuid = getFlowUuid();
        assertNotNull( uuid,"Flow UUID should not be null" );
        assertTrue(uuid.matches("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$"), "UUID doesn't match the expected pattern");
        LOGGER.debug("Flow UUID: " + uuid);
    }
    private String getFlowUuid() {
        return given(RAPIDPRO_API_REQUEST_SPEC)
            .get("flows.json")
            .then()
            .extract()
            .path("results.find { it.name == 'Specimen collection and shipment to NRL' }.uuid");
    }

}
