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

import biz.nellemann.hmci.Configuration.InfluxObject;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.BatchPoints;
import org.influxdb.dto.Point;
import org.influxdb.dto.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.lang.Thread.sleep;

class InfluxClient {

    private final static Logger log = LoggerFactory.getLogger(InfluxClient.class);

    //private static final int BATCH_ACTIONS_LIMIT = 5000;
    //private static final int BATCH_INTERVAL_DURATION = 1000;


    final private String url;
    final private String username;
    final private String password;
    final private String database;

    private InfluxDB influxDB;
    private BatchPoints batchPoints;
    private int errorCounter = 0;


    InfluxClient(InfluxObject config) {
        this.url = config.url;
        this.username = config.username;
        this.password = config.password;
        this.database = config.database;
    }


    synchronized void login() throws RuntimeException, InterruptedException {

        if(influxDB != null) {
            return;
        }

        boolean connected = false;
        int loginErrors = 0;

        do {
            try {
                log.debug("Connecting to InfluxDB - " + url);
                influxDB = InfluxDBFactory.connect(url, username, password);
                createDatabase();
                batchPoints = BatchPoints.database(database).precision(TimeUnit.SECONDS).build();
                connected = true;
            } catch(Exception e) {
                sleep(15 * 1000);
                if(loginErrors++ > 3) {
                    log.error("login() error, giving up - " + e.getMessage());
                    throw new RuntimeException(e);
                } else {
                    log.warn("login() error, retrying - " + e.getMessage());
                }
            }
        } while(!connected);

    }


    synchronized void logoff() {
        if(influxDB != null) {
            influxDB.close();
        }
        influxDB = null;
    }


    void createDatabase() {
        // Create our database... with a default retention of 156w == 3 years
        influxDB.query(new Query("CREATE DATABASE " + database + " WITH DURATION 156w"));
        influxDB.setDatabase(database);
    }


    synchronized void writeBatchPoints() throws Exception {
        log.debug("writeBatchPoints()");
        try {
            influxDB.writeWithRetry(batchPoints);
        } catch(Exception e) {
            log.error("writeBatchPoints() error - " + e.getMessage());
            if(++errorCounter > 5) {
                log.info("writeBatchPoints() forcing logout / login");
                errorCounter = 0;
                logoff();
                login();
            }
        }
    }



    /*
        Managed System
     */


    void writeManagedSystem(ManagedSystem system) {

        if(system.metrics == null) {
            log.debug("writeManagedSystem() - null metrics, skipping");
            return;
        }

        Instant timestamp = system.getTimestamp();
        if(timestamp == null) {
            log.warn("writeManagedSystem() - no timestamp, skipping");
            return;
        }

        getSystemMemory(system, timestamp).forEach( it -> batchPoints.point(it) );
        getSystemProcessor(system, timestamp).forEach( it -> batchPoints.point(it) );
        getSystemSharedProcessorPools(system, timestamp).forEach( it -> batchPoints.point(it) );
        getSystemSharedAdapters(system, timestamp).forEach( it -> batchPoints.point(it) );
        getSystemFiberChannelAdapters(system, timestamp).forEach( it -> batchPoints.point(it) );
        getSystemVirtualEthernetAdapters(system, timestamp).forEach( it -> batchPoints.point(it) );
        getSystemViosMemory(system, timestamp).forEach( it -> batchPoints.point(it) );
        getSystemViosProcessor(system, timestamp).forEach( it -> batchPoints.point(it) );

    }


    private static List<Point> getSystemMemory(ManagedSystem system, Instant timestamp) {
        List<Measurement> metrics = system.getMemoryMetrics();
        return processMeasurementMap(metrics, timestamp, "SystemMemory");
    }

    private static List<Point> getSystemProcessor(ManagedSystem system, Instant timestamp) {
        List<Measurement> metrics = system.getProcessorMetrics();
        return processMeasurementMap(metrics, timestamp, "SystemProcessor");
    }

    private static List<Point> getSystemSharedProcessorPools(ManagedSystem system, Instant timestamp) {
        List<Measurement> metrics = system.getSharedProcessorPools();
        return processMeasurementMap(metrics, timestamp, "SystemSharedProcessorPool");
    }

    private static List<Point> getSystemSharedAdapters(ManagedSystem system, Instant timestamp) {
        List<Measurement> metrics = system.getSystemSharedAdapters();
        return processMeasurementMap(metrics, timestamp, "SystemSharedAdapters");
    }

    private static List<Point> getSystemFiberChannelAdapters(ManagedSystem system, Instant timestamp) {
        List<Measurement> metrics = system.getSystemFiberChannelAdapters();
        return processMeasurementMap(metrics, timestamp, "SystemFiberChannelAdapters");
    }

    private static List<Point> getSystemVirtualEthernetAdapters(ManagedSystem system, Instant timestamp) {
        List<Measurement> metrics = system.getSystemVirtualEthernetAdapters();
        return processMeasurementMap(metrics, timestamp, "SystemVirtualEthernetAdapters");
    }

    private static List<Point> getSystemViosMemory(ManagedSystem system, Instant timestamp) {
        List<Measurement> metrics = system.getViosMemoryMetrics();
        return processMeasurementMap(metrics, timestamp, "SystemViosMemory");
    }

    private static List<Point> getSystemViosProcessor(ManagedSystem system, Instant timestamp) {
        List<Measurement> metrics = system.getViosProcessorMetrics();
        return processMeasurementMap(metrics, timestamp, "SystemViosProcessor");
    }


    /*
        Logical Partitions
     */

    void writeLogicalPartition(LogicalPartition partition) {

        if(partition.metrics == null) {
            log.warn("writeLogicalPartition() - null metrics, skipping");
            return;
        }

        Instant timestamp = partition.getTimestamp();
        if(timestamp == null) {
            log.warn("writeLogicalPartition() - no timestamp, skipping");
            return;
        }

        getPartitionAffinityScore(partition, timestamp).forEach( it -> batchPoints.point(it));
        getPartitionMemory(partition, timestamp).forEach( it -> batchPoints.point(it));
        getPartitionProcessor(partition, timestamp).forEach( it -> batchPoints.point(it));
        getPartitionVirtualEthernetAdapter(partition, timestamp).forEach( it -> batchPoints.point(it));
        getPartitionVirtualFiberChannelAdapter(partition, timestamp).forEach( it -> batchPoints.point(it));

    }

    private static List<Point> getPartitionAffinityScore(LogicalPartition partition, Instant timestamp) {
        List<Measurement> metrics = partition.getAffinityScore();
        return processMeasurementMap(metrics, timestamp, "PartitionAffinityScore");
    }

    private static List<Point> getPartitionMemory(LogicalPartition partition, Instant timestamp) {
        List<Measurement> metrics = partition.getMemoryMetrics();
        return processMeasurementMap(metrics, timestamp, "PartitionMemory");
    }

    private static List<Point> getPartitionProcessor(LogicalPartition partition, Instant timestamp) {
        List<Measurement> metrics = partition.getProcessorMetrics();
        return processMeasurementMap(metrics, timestamp, "PartitionProcessor");
    }

    private static List<Point> getPartitionVirtualEthernetAdapter(LogicalPartition partition, Instant timestamp) {
        List<Measurement> metrics = partition.getVirtualEthernetAdapterMetrics();
        return processMeasurementMap(metrics, timestamp, "PartitionVirtualEthernetAdapters");
    }

    private static List<Point> getPartitionVirtualFiberChannelAdapter(LogicalPartition partition, Instant timestamp) {
        List<Measurement> metrics = partition.getVirtualFiberChannelAdaptersMetrics();
        return processMeasurementMap(metrics, timestamp, "PartitionVirtualFiberChannelAdapters");
    }



    /*
        System Energy
        Not supported on older HMC (pre v8) or older Power server (pre Power 8)
     */


    void writeSystemEnergy(SystemEnergy system) {

        if(system.metrics == null) {
            log.debug("writeSystemEnergy() - null metrics, skipping");
            return;
        }

        Instant timestamp = system.getTimestamp();
        if(timestamp == null) {
            log.warn("writeSystemEnergy() - no timestamp, skipping");
            return;
        }

        getSystemEnergyPower(system, timestamp).forEach(it -> batchPoints.point(it) );
        getSystemEnergyTemperature(system, timestamp).forEach(it -> batchPoints.point(it) );
    }

    private static List<Point> getSystemEnergyPower(SystemEnergy system, Instant timestamp) {
        List<Measurement> metrics = system.getPowerMetrics();
        return processMeasurementMap(metrics, timestamp, "SystemEnergyPower");
    }

    private static List<Point> getSystemEnergyTemperature(SystemEnergy system, Instant timestamp) {
        List<Measurement> metrics = system.getThermalMetrics();
        return processMeasurementMap(metrics, timestamp, "SystemEnergyThermal");
    }


    /*
        Shared
     */

    private static List<Point> processMeasurementMap(List<Measurement> measurements, Instant timestamp, String measurement) {

        List<Point> listOfPoints = new ArrayList<>();
        measurements.forEach( m -> {

            // Iterate fields
            m.fields.forEach((fieldName, fieldValue) ->  {
                log.debug("processMeasurementMap() " + measurement + " - fieldName: " + fieldName + ", fieldValue: " + fieldValue);

                Point.Builder builder = Point.measurement(measurement)
                        .time(timestamp.toEpochMilli(), TimeUnit.MILLISECONDS)
                        .tag("name", fieldName)
                        .addField("value", fieldValue);

                // For each field, we add all tags
                m.tags.forEach((tagName, tagValue) -> {
                    builder.tag(tagName, tagValue);
                    log.debug("processMeasurementMap() " + measurement + " - tagName: " + tagName + ", tagValue: " + tagValue);
                });

                listOfPoints.add(builder.build());
            });

        });

        return listOfPoints;
    }


}
