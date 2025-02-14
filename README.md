DESCRIZIONE DEL PROGETTO:

Questo progetto mostra come integrare Bonita BPM in un'app Android. L'app esegue il login su Bonita, avvia un processo e completa una serie di user task associate all'istanza di processo. 
Le operazioni vengono effettuate tramite le API REST di Bonita, utilizzando la libreria OkHttp per le comunicazioni HTTP e una gestione personalizzata dei cookie per mantenere la sessione.

------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ 

FUNZIONALITà PRINCIPALI:

-Login su Bonita BPM: Effettua il login inviando username e password al servizio di autenticazione di Bonita, ottenendo i cookie di sessione.

-Avvio del Processo: Invia una richiesta per avviare un'istanza di un processo predefinito (identificato da PROCESS_DEFINITION_ID), senza passare variabili in ingresso.

--Completamento delle User Task:

-Completa la task "retrieve-smartphone-battery-level" inviando un valore casuale per la batteria del telefono.

-Completa la task "retrieve-smartwatch-battery-level" inviando un valore casuale per la batteria dello smartwatch.


--In base al confronto tra i livelli di batteria, il flusso si dirama in due rami:

---Se il livello della batteria del telefono è minore o uguale a quello dello smartwatch, viene completata la task "retrieve-temperature-from-smartwatch" seguita da "return-watch-temperature-to-app" per mostrare il valore.

---Se il livello della batteria del telefono è maggiore, viene completata la task "retrieve-temperature-from-smartphone" seguita da "return-phone-temperature-to-app" per mostrare il valore.


-Lettura delle Variabili di Processo: Recupera e visualizza i valori di variabili (come la temperatura) memorizzate nel processo.

------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

LIBRERIE E STANDARD UTILIZZATI:

OkHttpClient: Utilizzato per effettuare le chiamate HTTP alle API REST di Bonita.

JSON: Per il formato dei dati inviati e ricevuti.

CookieJar Personalizzato (MyCookieJar): Memorizza i cookie di sessione per mantenere l'autenticazione.

Interceptor (XBonitaTokenInterceptor): Aggiunge l'header X-Bonita-API-Token alle richieste se disponibile, garantendo la corretta autenticazione.

------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------


FLUSSO DI ESECUZIONE

1)LOGIN SU BONITA

All'avvio, l'utente preme il pulsante "Start Process".

Il metodo doLoginBonita() invia una richiesta POST a http://10.0.2.2:8080/bonita/loginservice con le credenziali definite.

Se il login ha successo, i cookie di sessione (incluso il token API) vengono salvati nel MyCookieJar.

Viene visualizzato un messaggio di conferma ("Login OK. Avvio processo...") e si passa al prossimo step.


2)AVVIO DEL PROCESSO

Il metodo startProcessInstance() invia una richiesta POST per creare una nuova istanza di processo all'endpoint:
/API/bpm/process/{PROCESS_DEFINITION_ID}/instantiation

La risposta contiene il caseId dell'istanza avviata, che viene mostrato all'utente.

Su un thread separato, viene eseguita la sequenza di completamento delle user task.


3) COMPLETAMENTO DELLE USER TASK

Task "retrieve-smartphone-battery-level"

Il metodo completePhoneTask() attende fino a 10 tentativi che la task diventi disponibile, la "claima" (claim) e la completa inviando un valore casuale per la batteria del telefono.

Task "retrieve-smartwatch-battery-level"

Il metodo completeWatchTask() attende la disponibilità della task, la clama e la completa inviando un valore casuale per la batteria dello smartwatch.

Task di Temperatura

Il metodo waitForOneOfTasks() attende che appaia una task con display name "retrieve-temperature-from-smartwatch" o "retrieve-temperature-from-smartphone".

Se viene rilevata la task "retrieve-temperature-from-smartwatch":

Viene completata con un valore casuale tramite completeTemperatureFromWatch().

Successivamente, il metodo waitForUserTask() attende la task "return-watch-temperature-to-app", la quale viene completata da completeReturnWatchTempTask() mostrando il valore di temperatura recuperato dal processo.

Se invece viene rilevata la task "retrieve-temperature-from-smartphone":

Viene completata con un valore casuale tramite completeTemperatureFromPhone().

Successivamente, viene completata la task "return-phone-temperature-to-app" tramite completeReturnPhoneTempTask() mostrando il valore di temperatura.



4)VISUALIZZAZIONE DEI RISULTATI

Durante l'esecuzione, il messaggio di stato e i risultati (come i valori di batteria e temperatura) vengono aggiornati e visualizzati nella TextView dell'interfaccia utente.







DIAGRAMMA BPMN PRESENTE SU BONITACOMMUNITYEDITION:

![image](https://github.com/user-attachments/assets/df7c28f4-5311-49a7-8a59-6f8684556ff6)

