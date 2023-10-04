package org.hisp.dhis.integration.rapidpro;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "mapping")
public class MappingProperties
{
    private Map<String,String> programStageToFlow = new HashMap<>();
    public Map<String, String> getProgramStageToFlow() {
        return programStageToFlow;
    }
    public boolean flowIdExists(String flowId) {
        return programStageToFlow.values().contains(flowId);
    }

    public String getFlowIdByProgramStageId(String programStageId) {
        return programStageToFlow.get(programStageId);
    }
    // FIXME: iterates through all entries. For small mappings this is fine, but should probably implement an inverse lookup table
    public String getProgramStageIdByFlowId(String flowId) {
        for (Map.Entry<String, String> entry : programStageToFlow.entrySet()) {
            if (entry.getValue().equals(flowId)) {
                return entry.getKey();
            }
        }
        return null;
    }

}
