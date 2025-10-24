package com.lsmed.connectors.wsReceiver.endpoint;

import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import com.lsmed.connectors.wsReceiver.IPatchStreamProcessor;
import com.lsmed.connectors.wsReceiver.ConfigurationManager; // Import ConfigurationManager

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class PresenseProcessor implements IPatchStreamProcessor {

    private static final Logger LOG = LogManager.getLogger(PresenseProcessor.class.getSimpleName());

    // Original API_KEY is now the Bearer token
    private static final String API_KEY = "814xqfGJWSVfKgfFOvQz24MLAuRsuDA3";
    private static String API_ENDPOINT;
    private static boolean WRITE_TO_FILE;
    private static final int chunk_size = 7;
    private static String source;

    public PresenseProcessor() {
        // Initialize configuration values using ConfigurationManager
        ConfigurationManager configManager = ConfigurationManager.getInstance();
        String useTestUrl = configManager.getProperty("use.test.url");
        source = configManager.getSource();
        if ("true".equalsIgnoreCase(useTestUrl)) {
            // Test URL is kept the same
            API_ENDPOINT = "https://staging-vitals.presense.icu/data";
            LOG.info("Using test URL for API endpoint");
        } else {
            // Default URL is changed
            API_ENDPOINT = "https://vitals.presense.icu/data";
            LOG.info("Using default URL for API endpoint");
        }

        String writeToFile = configManager.getProperty("write.to.file");
        WRITE_TO_FILE = "true".equalsIgnoreCase(writeToFile);
        if (WRITE_TO_FILE) {
            LOG.info("Writing data to file is enabled");
        } else {
            LOG.info("Writing data to file is disabled");
        }
    }

    public void processMessage(JSONObject payload, JSONObject context) throws IOException {
        if (context.has("Discharge")) {
            try {
                int response = sendDataToApi(payload);
                if (response >= 200 && response < 300) {
                    LOG.info("[" + payload.getString("PatchId") + "] Discharge message sent to API successfully with status: " + response);
                } else {
                    // Add this else block to log the failure
                    LOG.error("[" + payload.getString("PatchId") + "] Failed to send discharge message to API. Received status code: " + response);
                }

                if (WRITE_TO_FILE) {
                    LOG.info("Discharge Message");
                    writeToFile(payload);
                }
            } catch (Exception e) {
                LOG.error("Error sending discharge message to API.", e);
            }
        } else {
            if (context.has("SendStatus") || context.isEmpty()) {
                context.clear();
                context.put("count", 1);
                JSONArray patientDataArray = new JSONArray();
                patientDataArray.put(payload);
                context.put("PatientData", patientDataArray);
            } else if ((Integer) context.get("count") % chunk_size == chunk_size - 1) {
                int count = (Integer) context.get("count");
                count += 1;
                context.getJSONArray("PatientData").put(payload);
                context.put("count", count);
                int sendStatus = processBatch(context);
                context.clear();
                context.put("SendStatus", sendStatus);
            } else {
                int count = (Integer) context.get("count");
                count += 1;
                context.getJSONArray("PatientData").put(payload);
                context.put("count", count);
            }
        }
    }

    private int processBatch(JSONObject context) {
        JSONArray patientDataArray = context.getJSONArray("PatientData");

        JSONObject payloadFirst = patientDataArray.getJSONObject(0);
        JSONObject payloadLast = patientDataArray.getJSONObject(chunk_size - 1);
        JSONArray SensorDataLast = new JSONArray();

        JSONObject dataToOutput = new JSONObject();
        if (payloadLast.has("SensorDataDummy")) {
            SensorDataLast = payloadLast.getJSONArray("SensorDataDummy");
            dataToOutput.put("BiosensorStatus", "Disconnected");
        } else {
            SensorDataLast = payloadLast.getJSONArray("SensorData");
            dataToOutput.put("BiosensorStatus", "Connected");
        }

        JSONArray SensorDataBuffer = new JSONArray();
        long time = 0, ECGprev = -1;
        JSONArray sensorData = new JSONArray();

        for (int i = 0; i < chunk_size; i++) {
            JSONObject payload = patientDataArray.getJSONObject(i);
            sensorData = payload.getJSONArray("SensorData");
            long timeDiff = 0;
            for (int j = 0; j < sensorData.length(); j++) {

                if (!sensorData.isEmpty()) {
                    JSONObject SR = sensorData.getJSONObject(j);

                    if (ECGprev == -1) {
                        time = payload.getLong("SensProcTimeUs");
                        ECGprev = SR.getLong("TSECG");
                        SensorDataBuffer.put(processSensorData(SR, time));
                    } else {
                        timeDiff = SR.getLong("TSECG") - ECGprev;
                        ECGprev = SR.getLong("TSECG");
                        time = time + timeDiff;
                        SensorDataBuffer.put(processSensorData(SR, time));
                    }
                }
            }
        }

        dataToOutput.put("SensorData", SensorDataBuffer);

        String FacilityId = payloadFirst.getString("FacilityId");
        String PatientId = payloadFirst.getString("PatientId");
        String AdmissionId = payloadFirst.getString("AdmissionId");
        String patientRef = FacilityId + "-" + PatientId + "-" + AdmissionId;
        String PatchId = payloadFirst.getString("PatchId");
        String PGroupId = ""; // Default to an empty string or handle as you see fit
        if (payloadFirst.has("PGroupId") && !payloadFirst.isNull("PGroupId")) {
            PGroupId = payloadFirst.getString("PGroupId");
        } else {
            LOG.warn("[{}] PGroupId is missing or null in the payload", payloadFirst.getString("PatchId"));
        }
        String patientName = payloadFirst.getString("patientName");
        String pgroupName = payloadFirst.getString("pgroupName");
        String AdmissionTime = payloadFirst.getString("AdmissionTime");
        int age = payloadFirst.getInt("age");
        String gender = payloadFirst.getString("gender");

        dataToOutput.put("patientRef", patientRef);
        dataToOutput.put("FacilityId", FacilityId);
        dataToOutput.put("PatchId", PatchId);
        dataToOutput.put("BedId", PGroupId);
        dataToOutput.put("Location", pgroupName);
        dataToOutput.put("Age", age);
        dataToOutput.put("Gender", gender);
        dataToOutput.put("PatientName", patientName);
        dataToOutput.put("AdmissionTime", AdmissionTime);
        dataToOutput.put("source", source);

        dataToOutput.put("TimeStamp", payloadFirst.getLong("SensProcTimeUs"));

        JSONObject ewsInfo = payloadLast.getJSONObject("ewsInfo");
        // JSONArray Oxygen = ewsInfo.getJSONArray("Oxygen");
        // JSONArray Consciousness = ewsInfo.getJSONArray("Consciousness");
        // JSONArray BODYTEMP = ewsInfo.getJSONArray("BODYTEMP");
        // JSONObject ewsInfoRefined = new JSONObject();
        // ewsInfoRefined.put("Oxygen", Oxygen);
        // ewsInfoRefined.put("Consciousness", Consciousness);
        // ewsInfoRefined.put("BODYTEMP", BODYTEMP);
        JSONObject ews = new JSONObject();
        ews.put("ewsInfo", ewsInfo);
        dataToOutput.put("ews", ews);

        JSONArray ArrythmiaData = new JSONArray();
        if (payloadLast.has("ArrythmiaData") && !payloadLast.getJSONArray("ArrythmiaData").isEmpty()) {
            ArrythmiaData = payloadLast.getJSONArray("ArrythmiaData");
        }
        dataToOutput.put("ArrythmiaData", ArrythmiaData);

        JSONObject BP = new JSONObject();
        JSONObject SPO2 = new JSONObject();
        JSONObject PR = new JSONObject();
        for (int i = 0; i < SensorDataLast.length(); i++) {
            JSONObject SR = SensorDataLast.getJSONObject(i);
            if (SR.has("BP_VALID") && SR.getBoolean("BP_VALID")) {
                BP.put("IsValid", true);
                BP.put("Sys", SR.getJSONArray("BPSYS").get(0));
                BP.put("Dia", SR.getJSONArray("BPDIA").get(0));
                BP.put("TimeStamp", SR.getLong("BP_TIME"));
            }

            if (SR.has("PR_VALID") && SR.getBoolean("PR_VALID")) {
                PR.put("IsValid", true);
                PR.put("Value", SR.getJSONArray("PR").get(0));
                PR.put("TimeStamp", SR.getLong("PR_TIME"));
            }

            //&& SR.getInt("SPO2_ERROR") == 127
            if (SR.has("SPO2") && SR.getJSONArray("SPO2").getInt(0) <= 100) {
                SPO2.put("IsValid", true);
                SPO2.put("Value", SR.getJSONArray("SPO2").get(0));
                SPO2.put("TimeStamp", SR.getLong("SPO2_TIME"));
            }
        }
        dataToOutput.put("SPO2", SPO2);
        dataToOutput.put("PR", PR);
        dataToOutput.put("BP", BP);

        if (WRITE_TO_FILE) {
            writeToFile(dataToOutput);
        }

        int send = 0;
        try {
            send = sendDataToApi(dataToOutput);
        } catch (Exception e) {
            LOG.error("Error sending data to API.", e);
        }

        return send;
    }

    private void writeToFile(JSONObject jsonData) {
        try {
            String patchId = jsonData.getString("PatchId");
            long timestamp = jsonData.getLong("TimeStamp");

            String filename = patchId + "_" + timestamp + ".json";

            try (FileWriter file = new FileWriter(filename)) {
                file.write(jsonData.toString(2));
                LOG.info("Successfully wrote JSON Object to file: " + filename);
            } catch (IOException e) {
                LOG.error("An error occurred while writing to the file: " + e.getMessage());
            }
        } catch (Exception e) {
            LOG.error("An error occurred while extracting data or creating the filename: " + e.getMessage());
        }
    }

    private int sendDataToApi(JSONObject jsonData) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(API_ENDPOINT))
                // Change the header to use a Bearer token instead of an API key
                .header("Authorization", "Bearer " + API_KEY)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonData.toString()))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        // LOG.info(jsonData.getString("PatchId") + " " + response + "" + response.body());

        return response.statusCode();
    }

    private JSONObject processSensorData(JSONObject SR, long time) {
        JSONObject dataToOutput = new JSONObject();
        Number n = -1;

        long SEQ = SR.getLong("Seq");
        dataToOutput.put("SEQ", SEQ);

        long TSECG = SR.getLong("TSECG");
        dataToOutput.put("TSECG", TSECG);

        if (SR.has("HR") && !SR.getJSONArray("HR").isEmpty()) {
            int HR = SR.getJSONArray("HR").getInt(0);
            dataToOutput.put("HR", HR);
        } else {
            dataToOutput.put("HR", n);
        }

        if (SR.has("RR") && !SR.getJSONArray("RR").isEmpty()) {
            int RR = SR.getJSONArray("RR").getInt(0);
            dataToOutput.put("RR", RR);
        } else {
            dataToOutput.put("RR", n);
        }

        // JSONArray ECG_CH_A = SR.getJSONArray("ECG_CH_A");
        // dataToOutput.put("ECG_CH_A", ECG_CH_A);

        // JSONArray ECG_CH_B = SR.getJSONArray("ECG_CH_B");
        // dataToOutput.put("ECG_CH_B", ECG_CH_B);

        if (SR.has("SKINTEMP") && !SR.getJSONArray("SKINTEMP").isEmpty()) {
            double SKINTEMP = SR.getJSONArray("SKINTEMP").getDouble(0);
            dataToOutput.put("SKINTEMP", SKINTEMP / 1000);
        } else {
            dataToOutput.put("SKINTEMP", n);
        }

        if (SR.has("BODYTEMP") && !SR.getJSONArray("BODYTEMP").isEmpty()) {
            double BODYTEMP = SR.getJSONArray("BODYTEMP").getDouble(0);
            if (BODYTEMP == -1) {
                dataToOutput.put("BODYTEMP", -1);
            } else {
                dataToOutput.put("BODYTEMP", BODYTEMP / 1000);
            }
        } else {
            dataToOutput.put("BODYTEMP", n);
        }

        if (SR.has("AMBTEMP_AVG") && !SR.getJSONArray("AMBTEMP_AVG").isEmpty()) {
            double AMBTEMP_AVG = SR.getJSONArray("AMBTEMP_AVG").getDouble(0);
            dataToOutput.put("AMBTEMP_AVG", AMBTEMP_AVG / 100);
        } else {
            dataToOutput.put("AMBTEMP_AVG", n);
        }

        dataToOutput.put("TimeStamp", time);

        return dataToOutput;
    }
}