package biz.nellemann.hmci

import biz.nellemann.hmci.pojo.PcmData
import biz.nellemann.hmci.pojo.SystemUtil
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

@Slf4j
class LogicalPartition {

    public String id
    public String name
    public String type
    public String systemId

    protected PcmData metrics

    LogicalPartition(String id, String systemId) {
        this.id = id
        this.systemId = systemId
    }

    String toString() {
        return "[${id}] ${name} (${type})"
    }


    void processMetrics(String json) {
        log.debug("processMetrics()")
        def pcmMap = new JsonSlurper().parseText(json)
        metrics = new PcmData(pcmMap as Map)
    }

}
