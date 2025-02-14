package com.example.bonita;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private Button buttonStartProcess;
    private TextView textViewResult;

    // URL localhost
    private static final String BONITA_BASE_URL = "http://10.0.2.2:8080/bonita";
    // Credenziali default di Bonita
    private static final String BONITA_USERNAME = "walter.bates";
    private static final String BONITA_PASSWORD = "bpm";


    private static final long PROCESS_DEFINITION_ID = 5859370375779585322L;

    // Client OkHttp e gestione cookie
    private OkHttpClient httpClient;
    private MyCookieJar cookieJar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        buttonStartProcess = findViewById(R.id.buttonStartProcess);
        textViewResult = findViewById(R.id.textViewResult);

        // Inizializzo il CookieJar per memorizzare la sessione
        cookieJar = new MyCookieJar();

        // Costruisco il client con l'interceptor che aggiunge X-Bonita-API-Token
        httpClient = new OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .addInterceptor(new XBonitaTokenInterceptor(cookieJar))
                .build();

        // Al click faccio login su Bonita e poi avvio l'intero flusso
        buttonStartProcess.setOnClickListener(view -> {
            textViewResult.setText("Inizio login a Bonita...");
            doLoginBonita();
        });
    }

    // Effettua il login su Bonita per ottenere i cookie di sessione
    private void doLoginBonita() {
        RequestBody formBody = new FormBody.Builder()
                .add("username", BONITA_USERNAME)
                .add("password", BONITA_PASSWORD)
                .add("redirect", "false")
                .build();

        String loginUrl = BONITA_BASE_URL + "/loginservice";

        Request request = new Request.Builder()
                .url(loginUrl)
                .post(formBody)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() ->
                        textViewResult.setText("Login fallito: " + e.getMessage())
                );
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    runOnUiThread(() -> textViewResult.setText("Login OK. Avvio processo..."));
                    startProcessInstance();
                } else {
                    runOnUiThread(() ->
                            textViewResult.setText("Login non riuscito, codice: " + response.code())
                    );
                }
            }
        });
    }

    //Avvio il processo senza parametri iniziali (senza variabili in entrata nello start event)
    private void startProcessInstance() {
        JSONObject mainJson = new JSONObject();   // Senza variabili in ingresso
        String requestBodyStr = mainJson.toString();

        String url = BONITA_BASE_URL + "/API/bpm/process/" + PROCESS_DEFINITION_ID + "/instantiation";

        RequestBody body = RequestBody.create(
                requestBodyStr,
                MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    textViewResult.setText("Avvio processo fallito: " + e.getMessage());
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                        textViewResult.setText("Avvio processo non riuscito. Codice: " + response.code());
                    });
                    return;
                }

                String respStr = response.body().string();
                long caseId = -1;
                try {
                    JSONObject jsonResp = new JSONObject(respStr);
                    caseId = jsonResp.getLong("caseId");
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                long finalCaseId = caseId;
                runOnUiThread(() -> {
                    textViewResult.setText(
                            "Process avviato con successo.\nResponse: " + respStr
                                    + "\ncaseId=" + finalCaseId
                    );
                });

                // Eseguo in sequenza le task su un thread separato
                new Thread(() -> {
                    try {
                        // 1) retrieve-smartphone-battery-level
                        completePhoneTask(finalCaseId);

                        // 2) retrieve-smartwatch-battery-level
                        completeWatchTask(finalCaseId);

                        // 3) Al gateway bonita creerà:
                        //    - retrieve-temperature-from-smartwatch (se phoneBattery <= watchBattery)
                        //         -> return-watch-temperature-to-app
                        //    - retrieve-temperature-from-smartphone (se phoneBattery > watchBattery)
                        //         -> return-phone-temperature-to-app
                        // Cerco una delle due:
                        String nextTaskId = waitForOneOfTasks(finalCaseId,
                                "retrieve-temperature-from-smartwatch",
                                "retrieve-temperature-from-smartphone");
                        if (nextTaskId == null) {
                            runOnUiThread(() ->
                                    textViewResult.append("\nNessuna task di 'retrieve-temperature' trovata (timeout).")
                            );
                            return;
                        }

                        // Determino quale delle due ho trovato
                        String displayName = getUserTaskDisplayName(nextTaskId);
                        if ("retrieve-temperature-from-smartwatch".equals(displayName)) {
                            // Ramo 1: phoneBattery <= watchBattery
                            completeTemperatureFromWatch(finalCaseId, nextTaskId);

                            // Poi cerco "return-watch-temperature-to-app"
                            String watchReturnId = waitForUserTask(finalCaseId, "return-watch-temperature-to-app");
                            if (watchReturnId != null) {
                                completeReturnWatchTempTask(finalCaseId, watchReturnId);
                            } else {
                                runOnUiThread(() ->
                                        textViewResult.append("\nTask 'return-watch-temperature-to-app' non trovata (timeout).")
                                );
                            }

                        } else {
                            // Ramo 2: phoneBattery > watchBattery
                            completeTemperatureFromPhone(finalCaseId, nextTaskId);

                            // Poi cerco "return-phone-temperature-to-app"
                            String phoneReturnId = waitForUserTask(finalCaseId, "return-phone-temperature-to-app");
                            if (phoneReturnId != null) {
                                completeReturnPhoneTempTask(finalCaseId, phoneReturnId);
                            } else {
                                runOnUiThread(() ->
                                        textViewResult.append("\nTask 'return-phone-temperature-to-app' non trovata (timeout).")
                                );
                            }
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();
            }
        });
    }

    /**
     * 1) Attendo la User Task "retrieve-smartphone-battery-level",
     * 2) La claimo,
     * 3) La completo inviando phoneBattery random.
     */
    private void completePhoneTask(long caseId) throws Exception {
        // Cerco la task (max 10 tentativi, 1s di sleep ciascuno)
        String phoneTaskId = waitForUserTask(caseId, "retrieve-smartphone-battery-level");
        if (phoneTaskId == null) {
            runOnUiThread(() ->
                    textViewResult.setText("Nessuna task 'retrieve-smartphone-battery-level' trovata (timeout).")
            );
            return;
        }

        // Simulo il livello di batteria, a differenza del progetto di camunda
        int phoneBattery = new Random().nextInt(100) + 1;

        claimUserTask(phoneTaskId);
        // Contratto = {"phoneBattery": <valore>}   (contratto configurato su BonitaStudioCommunity)
        completeUserTask(phoneTaskId, "phoneBattery", phoneBattery);

        runOnUiThread(() ->
                textViewResult.setText(
                        textViewResult.getText() + "\nCompletata smartphone battery con livello: " + phoneBattery
                )
        );
    }

    /**
     * 1) Attendo la User Task "retrieve-smartwatch-battery-level",
     * 2) La claimo,
     * 3) La completo inviando watchBattery random.
     */
    private void completeWatchTask(long caseId) throws Exception {
        String watchTaskId = waitForUserTask(caseId, "retrieve-smartwatch-battery-level");
        if (watchTaskId == null) {
            runOnUiThread(() ->
                    textViewResult.setText("Nessuna task 'retrieve-smartwatch-battery-level' trovata (timeout).")
            );
            return;
        }

        // Simulo il livello di batteria del watch, a differenza del progetto di camunda
        int watchBattery = new Random().nextInt(100) + 1;

        claimUserTask(watchTaskId);
        // Contratto = {"watchBattery": <valore>}
        completeUserTask(watchTaskId, "watchBattery", watchBattery);

        runOnUiThread(() ->
                textViewResult.setText(
                        textViewResult.getText() + "\nCompletata smartwatch battery con livello: " + watchBattery
                )
        );
    }

    // -------------------------------------------------------------------
    //  retrieve-temperature-from-smartwatch  e  return-watch-temperature-to-app
    // -------------------------------------------------------------------

    /**
     * retrieve-temperature-from-smartwatch:
     * Completa la task inviando watchTemperature random (25-30°C).
     */
    private void completeTemperatureFromWatch(long caseId, String taskId) throws Exception {
        float watchTemp = 25 + new Random().nextFloat() * 5; // simulo temperatura

        claimUserTask(taskId);
        // Contratto = {"watchTemperature": <valore float>}
        completeUserTaskFloat(taskId, "watchTemperature", watchTemp);

        runOnUiThread(() ->
                textViewResult.append("\nCompletata retrieve-temperature-from-smartwatch: " + watchTemp + "°C")
        );
    }

    /**
     * return-watch-temperature-to-app:
     * - Legge la variabile di processo "watchTemperature"
     * - La mostra a video
     * - Completa la task senza contract
     */
    private void completeReturnWatchTempTask(long caseId, String taskId) throws Exception {
        // Leggo la variabile "watchTemperature"
        String watchTempVal = readCaseVariable(caseId, "watchTemperature");
        runOnUiThread(() ->
                textViewResult.append("\nwatchTemperature dal processo: " + watchTempVal + "°C")
        );

        // Completo la task con JSON vuoto
        claimUserTask(taskId);
        completeUserTaskNoContract(taskId);

        runOnUiThread(() ->
                textViewResult.append("\nCompletata return-watch-temperature-to-app.")
        );
    }

    // -------------------------------------------------------------------
    //  retrieve-temperature-from-smartphone  e  return-phone-temperature-to-app
    // -------------------------------------------------------------------

    /**
     * retrieve-temperature-from-smartphone:
     * Completa la task inviando phoneTemperature random (25-35°C).
     * (Se vuoi leggerla davvero dal telefono, sostituisci con un metodo reale)
     */
    private void completeTemperatureFromPhone(long caseId, String taskId) throws Exception {
        float phoneTemp = 25 + new Random().nextFloat() * 10; // simulo

        claimUserTask(taskId);
        completeUserTaskFloat(taskId, "phoneTemperature", phoneTemp);

        runOnUiThread(() ->
                textViewResult.append("\nCompletata retrieve-temperature-from-smartphone: " + phoneTemp + "°C")
        );
    }

    /**
     * return-phone-temperature-to-app:
     * - Legge la variabile di processo "phoneTemperature"
     * - La mostra a video
     * - Completa la task senza contract
     */
    private void completeReturnPhoneTempTask(long caseId, String taskId) throws Exception {
        String phoneTempVal = readCaseVariable(caseId, "phoneTemperature");
        runOnUiThread(() ->
                textViewResult.append("\nphoneTemperature dal processo: " + phoneTempVal + "°C")
        );

        claimUserTask(taskId);
        completeUserTaskNoContract(taskId);

        runOnUiThread(() ->
                textViewResult.append("\nCompletata return-phone-temperature-to-app.")
        );
    }

    // -------------------------------------------------------------------
    //                    FUNZIONI DI SUPPORTO
    // -------------------------------------------------------------------

    /**
     * Tenta fino a 10 volte di trovare UNA tra due possibili task
     * (displayName=taskName1 o taskName2). Se la trova, restituisce l'id,
     * altrimenti null.
     */
    private String waitForOneOfTasks(long caseId, String taskName1, String taskName2) throws Exception {
        for (int i = 0; i < 10; i++) {
            String t1 = getUserTaskIdByName(caseId, taskName1);
            if (t1 != null) return t1;

            String t2 = getUserTaskIdByName(caseId, taskName2);
            if (t2 != null) return t2;

            Thread.sleep(1000);
        }
        return null;
    }

    /**
     * Tenta fino a 10 volte (con 1s di sleep) di trovare la User Task
     * con displayName = <nome> per un dato caseId.     DA MIGLIORARE
     */
    private String waitForUserTask(long caseId, String displayName) throws Exception {
        for (int i = 0; i < 10; i++) {
            String taskId = getUserTaskIdByName(caseId, displayName);
            if (taskId != null) {
                return taskId;
            }
            Thread.sleep(1000); // attendo 1 secondo prima di riprovare
        }
        return null;
    }

    /**
     * Ritorna il displayName di una task data l'id
     */
    private String getUserTaskDisplayName(String taskId) throws IOException, JSONException {
        String url = BONITA_BASE_URL + "/API/bpm/humanTask/" + taskId;
        Request req = new Request.Builder().url(url).get().build();

        Response resp = httpClient.newCall(req).execute();
        String body = resp.body().string();
        resp.close();

        JSONObject obj = new JSONObject(body);
        return obj.optString("displayName", "");
    }

    /**
     * Ritorna l'id della prima User Task con caseId = X e displayName = Y (se esiste).
     */
    private String getUserTaskIdByName(long caseId, String displayName) throws IOException, JSONException {
        // ENDPOINT BONITA
        String url = BONITA_BASE_URL + "/API/bpm/humanTask?c=10&p=0&f=caseId=" + caseId;

        Request req = new Request.Builder().url(url).get().build();

        Response resp = httpClient.newCall(req).execute();
        if (!resp.isSuccessful()) {
            return null;
        }
        String respStr = resp.body().string();
        resp.close();

        JSONArray arr = new JSONArray(respStr);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject taskObj = arr.getJSONObject(i);
            String taskDisplayName = taskObj.optString("displayName", "");
            if (displayName.equals(taskDisplayName)) {
                return taskObj.optString("id", null);
            }
        }
        return null;
    }

    /**
     * Claim della task (assegnarla all'utente loggato)
     */
    private void claimUserTask(String taskId) throws IOException, JSONException {
        String userId = getLoggedUserId();
        String url = BONITA_BASE_URL + "/API/bpm/humanTask/" + taskId;

        JSONObject assignJson = new JSONObject();
        assignJson.put("assigned_id", userId);

        RequestBody body = RequestBody.create(
                assignJson.toString(),
                MediaType.get("application/json; charset=utf-8")
        );

        Request putReq = new Request.Builder()
                .url(url)
                .put(body)
                .build();

        Response resp = httpClient.newCall(putReq).execute();
        resp.close();
    }

    /**
     * Completa la task inviando un JSON
     */
    private void completeUserTask(String taskId, String contractName, int contractValue) throws IOException {
        String url = BONITA_BASE_URL + "/API/bpm/userTask/" + taskId + "/execution";

        JSONObject json = new JSONObject();
        try {
            json.put(contractName, contractValue);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        RequestBody reqBody = RequestBody.create(
                json.toString(),
                MediaType.get("application/json; charset=utf-8")
        );

        Request postReq = new Request.Builder()
                .url(url)
                .post(reqBody)
                .build();

        Response resp = httpClient.newCall(postReq).execute();
        resp.close();
    }

    /**
     * Completa la task inviando un float
     */
    private void completeUserTaskFloat(String taskId, String contractName, float contractValue) throws IOException {
        String url = BONITA_BASE_URL + "/API/bpm/userTask/" + taskId + "/execution";

        JSONObject json = new JSONObject();
        try {
            json.put(contractName, contractValue);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        RequestBody reqBody = RequestBody.create(
                json.toString(),
                MediaType.get("application/json; charset=utf-8")
        );

        Request postReq = new Request.Builder()
                .url(url)
                .post(reqBody)
                .build();

        Response resp = httpClient.newCall(postReq).execute();
        resp.close();
    }

    /**
     * Completa la task senza contract
     */
    private void completeUserTaskNoContract(String taskId) throws IOException {
        String url = BONITA_BASE_URL + "/API/bpm/userTask/" + taskId + "/execution";

        JSONObject json = new JSONObject(); // "{}"

        RequestBody reqBody = RequestBody.create(
                json.toString(),
                MediaType.get("application/json; charset=utf-8")
        );

        Request postReq = new Request.Builder()
                .url(url)
                .post(reqBody)
                .build();

        Response resp = httpClient.newCall(postReq).execute();
        resp.close();
    }

    /**
     * Restituisce l'ID dell'utente loggato.
     */
    private String getLoggedUserId() throws IOException, JSONException {
        String url = BONITA_BASE_URL + "/API/identity/user?f=userName=" + BONITA_USERNAME;
        Request request = new Request.Builder().url(url).get().build();

        Response resp = httpClient.newCall(request).execute();
        String body = resp.body().string();
        resp.close();


        JSONArray arr = new JSONArray(body);
        if (arr.length() > 0) {
            return arr.getJSONObject(0).optString("id", "");
        }
        return "";
    }

    /**
     * Legge una variabile di processo via /API/bpm/caseVariable/{caseId}/{varName}
     */
    private String readCaseVariable(long caseId, String varName) {
        String url = BONITA_BASE_URL + "/API/bpm/caseVariable/" + caseId + "/" + varName;

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try {
            Response response = httpClient.newCall(request).execute();
            if (!response.isSuccessful()) {
                return null;
            }
            String respBody = response.body().string();
            JSONObject jsonObj = new JSONObject(respBody);
            // "value" può essere "null" (stringa) se la variabile non è valorizzata
            return jsonObj.optString("value", "null");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }


    // --------------------------------------------------------
    //                CLASSI INTERNE di SUPPORTO
    // --------------------------------------------------------

    /**
     * CookieJar in-memory per memorizzare i cookie di sessione di Bonita
     */
    private static class MyCookieJar implements CookieJar {
        private final java.util.List<Cookie> cookieStore = new java.util.ArrayList<>();

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            // Aggiorno i cookie con quelli nuovi
            for (Cookie newCookie : cookies) {
                cookieStore.removeIf(oldCookie ->
                        oldCookie.name().equals(newCookie.name()) && oldCookie.domain().equals(newCookie.domain())
                );
                cookieStore.add(newCookie);
            }
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            return cookieStore;
        }

        // Ritorno il token Bonita (se presente) per l'header
        public String getBonitaApiToken() {
            for (Cookie c : cookieStore) {
                if (c.name().equals("X-Bonita-API-Token")) {
                    return c.value();
                }
            }
            return null;
        }
    }

    /**
     * Interceptor che aggiunge l'header X-Bonita-API-Token
     */
    private static class XBonitaTokenInterceptor implements Interceptor {
        private final MyCookieJar myCookieJar;

        public XBonitaTokenInterceptor(MyCookieJar jar) {
            this.myCookieJar = jar;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request original = chain.request();
            // Applico l'header solo se la chiamata è verso 10.0.2.2
            if (original.url().host().equals("10.0.2.2")) {
                String token = myCookieJar.getBonitaApiToken();
                if (token != null) {
                    Request newReq = original.newBuilder()
                            .header("X-Bonita-API-Token", token)
                            .build();
                    return chain.proceed(newReq);
                }
            }
            return chain.proceed(original);
        }
    }
}
