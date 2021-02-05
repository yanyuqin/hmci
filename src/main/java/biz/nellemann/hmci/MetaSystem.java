/*
 *    Copyright 2020 Mark Nellemann <mark.nellemann@gmail.com>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package biz.nellemann.hmci;

import biz.nellemann.hmci.pcm.PcmData;
import com.serjltt.moshi.adapters.FirstElement;
import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.ToJson;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

abstract class MetaSystem {

    private final static Logger log = LoggerFactory.getLogger(MetaSystem.class);

    private final JsonAdapter<PcmData> jsonAdapter;

    protected PcmData metrics;

    MetaSystem() {
        try {
            Moshi moshi = new Moshi.Builder().add(new NumberAdapter()).add(new BigDecimalAdapter()).add(FirstElement.ADAPTER_FACTORY).build();
            jsonAdapter = moshi.adapter(PcmData.class);
        } catch(Exception e) {
            log.warn("MetaSystem() error", e);
            throw new ExceptionInInitializerError(e);
        }
    }


    void processMetrics(String json) {

        try {
            metrics = jsonAdapter.nullSafe().fromJson(json);
        } catch(IOException e) {
            log.warn("processMetrics() error", e);
        }
        //System.out.println(jsonAdapter.toJson(metrics));

    }


    Instant getTimestamp()  {

        String timestamp = getStringMetricObject(metrics.systemUtil.sample.sampleInfo.timeStamp);
        Instant instant = Instant.now();
        try {
            log.trace("getTimeStamp() - PMC Timestamp: " + timestamp);
            DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[XXX][X]");
            instant = Instant.from(dateTimeFormatter.parse(timestamp));
            log.trace("getTimestamp() - Instant: " + instant.toString());
        } catch(DateTimeParseException e) {
            log.warn("getTimestamp() - parse error: " + timestamp);
        }

        return instant;
    }


    String getStringMetricObject(Object obj) {
        String metric = null;
        try {
            metric = (String) obj;
        } catch (NullPointerException npe) {
            log.warn("getStringMetricObject()", npe);
        }

        return metric;
    }


    Number getNumberMetricObject(Object obj) {
        Number metric = null;
        try {
            metric = (Number) obj;
        } catch (NullPointerException npe) {
            log.warn("getNumberMetricObject()", npe);
        }

        return metric;
    }


    static class BigDecimalAdapter {

        @FromJson
        BigDecimal fromJson(String string) {
            return new BigDecimal(string);
        }

        @ToJson
        String toJson(BigDecimal value) {
            return value.toString();
        }
    }


    static class NumberAdapter {

        @FromJson
        Number fromJson(String string) {
            return Double.parseDouble(string);
        }

        @ToJson
        String toJson(Number value) {
            return value.toString();
        }
    }

}
