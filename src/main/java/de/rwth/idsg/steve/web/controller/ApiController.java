package de.rwth.idsg.steve.web.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.rwth.idsg.steve.ocpp.CommunicationTask;
import de.rwth.idsg.steve.ocpp.OcppTransport;
import de.rwth.idsg.steve.ocpp.OcppVersion;
import de.rwth.idsg.steve.ocpp.RequestResult;
import de.rwth.idsg.steve.repository.*;
import de.rwth.idsg.steve.repository.dto.*;
import de.rwth.idsg.steve.service.ChargePointHelperService;
import de.rwth.idsg.steve.service.ChargePointService16_Client;
import de.rwth.idsg.steve.service.TransactionStopService;
import de.rwth.idsg.steve.utils.ConnectorStatusFilter;
import de.rwth.idsg.steve.web.dto.OcppTagForm;
import de.rwth.idsg.steve.web.dto.OcppTagQueryForm;
import de.rwth.idsg.steve.web.dto.TransactionQueryForm;
import de.rwth.idsg.steve.web.dto.UserQueryForm;
import de.rwth.idsg.steve.web.dto.ocpp.RemoteStartTransactionParams;
import de.rwth.idsg.steve.web.dto.ocpp.RemoteStopTransactionParams;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletResponse;
import jooq.steve.db.tables.records.AddressRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@CrossOrigin
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class ApiController {

  private final String sCHARGEBOXID = "/{chargeBoxId}";

  @Autowired
  protected ChargePointHelperService chargePointHelperService;

  @Autowired
  private ChargePointRepository chargePointRepository;

  private ObjectMapper objectMapper;

  @Autowired
  @Qualifier("ChargePointService16_Client")
  private ChargePointService16_Client client16;

  @Autowired
  private TransactionRepository transactionRepository;

  @Autowired
  private TaskStore taskStore;

  @Autowired
  private TransactionStopService transactionStopService;

  @Autowired
  private OcppTagRepository ocppTagRepository;

  @Autowired
  private UserRepository userRepository;

  @PostConstruct
  private void init() {
    objectMapper = new ObjectMapper();
    objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
  }

  @GetMapping(value = sCHARGEBOXID + "/connectorIds")
  public void getConnectorIds(
    @PathVariable("chargeBoxId") String chargeBoxId,
    HttpServletResponse response
  )
    throws IOException {
    String s = serializeArray(
      chargePointRepository.getNonZeroConnectorIds(chargeBoxId)
    );
    writeOutput(response, s);
  }

  @GetMapping(value = sCHARGEBOXID + "/startSession/{idTag}")
  public void startRemoteSession(
    @PathVariable("chargeBoxId") String chargeBoxId,
    @PathVariable("idTag") String idTag,
    HttpServletResponse response
  )
    throws IOException {
    try {
      if (!getTokenList(idTag).isEmpty()) {
        RemoteStartTransactionParams params = new RemoteStartTransactionParams();
        params.setIdTag(idTag);
        params.setConnectorId(0);
        List<ChargePointSelect> cp = new ArrayList<>();
        ChargePointSelect cps = new ChargePointSelect(
          OcppTransport.JSON,
          chargeBoxId
        );
        cp.add(cps);
        params.setChargePointSelectList(cp);
        CommunicationTask task = taskStore.get(
          client16.remoteStartTransaction(params)
        );

        while (!task.isFinished() || task.getResultMap().size() > 1) {
          System.out.println("wait for");
        }
        RequestResult result = (RequestResult) task
          .getResultMap()
          .get(chargeBoxId);
        if (result.getResponse() == null) {
          response.setStatus(HttpServletResponse.SC_PRECONDITION_FAILED);
        } else if (!result.getResponse().equals("Accepted")) {
          response.setStatus(HttpServletResponse.SC_FORBIDDEN);
          writeOutput(
            response,
            objectMapper.writeValueAsString(result.getResponse())
          );
        } else {
          writeOutput(
            response,
            objectMapper.writeValueAsString(result.getResponse())
          );
        }
      }
    } catch (NullPointerException nullPointerException) {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    }
  }

  @GetMapping(value = sCHARGEBOXID + "/stopSession/{ocpp_parent}")
  public void stopRemoteSession(
    @PathVariable("chargeBoxId") String chargeBoxId,
    @PathVariable("ocpp_parent") String ocpp_parent,
    HttpServletResponse response
  )
    throws IOException {
    try {
      RemoteStopTransactionParams params = new RemoteStopTransactionParams();
      List<Integer> transactionIDs = transactionRepository.getActiveTransactionIds(
        chargeBoxId
      );
      if (transactionIDs.size() > 0) {
        List<String> tokenList = new ArrayList<>();
        getTokenList(ocpp_parent).forEach(token -> tokenList.add(token.get(0)));
        if (
          tokenList.contains(
            transactionRepository
              .getDetails(transactionIDs.get(transactionIDs.size() - 1))
              .getTransaction()
              .getOcppIdTag()
          )
        ) {
          params.setTransactionId(
            transactionIDs.get(transactionIDs.size() - 1)
          );
          List<ChargePointSelect> cp = new ArrayList<>();
          ChargePointSelect cps = new ChargePointSelect(
            OcppTransport.JSON,
            chargeBoxId
          );
          cp.add(cps);
          params.setChargePointSelectList(cp);
          CommunicationTask task = taskStore.get(
            client16.remoteStopTransaction(params)
          );
          while (!task.isFinished() || task.getResultMap().size() > 1) {
            System.out.println("wait for");
          }
          RequestResult result = (RequestResult) task
            .getResultMap()
            .get(chargeBoxId);
          transactionStopService.stop(transactionIDs);
          writeOutput(
            response,
            objectMapper.writeValueAsString(result.getResponse())
          );
        } else {
          response.setStatus(HttpServletResponse.SC_FORBIDDEN);
          writeOutput(
            response,
            objectMapper.writeValueAsString("Not your charging session")
          );
        }
      } else {
        response.setStatus(HttpServletResponse.SC_CONFLICT);
        response.setHeader("Access-Control-Allow-Origin", "*");
      }
    } catch (NullPointerException nullPointerException) {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    }
  }

  @GetMapping("/getColonnine")
  public void getColonnine(HttpServletResponse response) throws IOException {
    List<String> chargingPoints = new ArrayList<>();
    chargingPoints = chargePointRepository.getChargeBoxIds();

    List<String> boxList = new ArrayList<>();

    try {
      Map<String, Map<String, String>> cbDetailsFinal = new LinkedHashMap<>();

      chargingPoints.forEach(
        chPoint -> {
          Map<String, String> map_temp = new LinkedHashMap<>();
          String temp;

          boxList.add(chPoint);
          ChargePoint.Details cp = chargePointRepository.getDetails(
            chargePointRepository.getChargeBoxIdPkPair(boxList).get(chPoint)
          );

          // inserimento valori - identificatore
          map_temp.put("id", chPoint.toString());

          // inserimento valori - titolare
          if (cp.getChargeBox().getChargePointVendor() == null) temp =
            "null"; else temp = cp.getChargeBox().getChargePointVendor();
          map_temp.put("titolare", temp);

          // inserimento valori - tipo ricarica
          if (cp.getChargeBox().getDescription() == null) temp =
            "null"; else temp = cp.getChargeBox().getDescription();
          map_temp.put("tipo_ricar", temp);

          // inserimento valori - longitudine
          String longt = String.valueOf(
            cp.getChargeBox().getLocationLongitude()
          );
          map_temp.put("longitudine", longt);

          // inserimento valori - latitudine
          String lat = String.valueOf(cp.getChargeBox().getLocationLatitude());
          map_temp.put("latitudine", lat);

          // inserimento valori - indirizzo
          Map<String, String> address = new HashMap<String, String>();
          address = getAddress(cp);

          if (address == null) {
            map_temp.put("via", "null");
            map_temp.put("citta", "null");
          } else {
            if (address.get("numero_civico") == "null") {
              map_temp.put("via", address.get("via"));
            } else {
              map_temp.put(
                "via",
                address.get("via") + " " + address.get("numero_civico")
              );
            }
            map_temp.put("citta", address.get("citta"));
          }

          // inserimento valori - stato (disponibile, occupato, ...)
          List<ConnectorStatus> latestList = chargePointRepository.getChargePointConnectorStatus();
          List<ConnectorStatus> filteredList = ConnectorStatusFilter.filterAndPreferZero(
            latestList
          );
          Optional<ConnectorStatus> optionalFilteredList = filteredList
            .stream()
            .parallel()
            .filter(cs -> chPoint.equals(cs.getChargeBoxId()))
            .findAny();
          if (optionalFilteredList.isEmpty()) temp = "null"; else temp =
            optionalFilteredList.orElse(null).getStatus();

          map_temp.put("stato", temp);

          // inserimento della colonnina all'interno della lista
          cbDetailsFinal.put(chPoint.toString(), map_temp);
        }
      );

      String s = serializeArray(cbDetailsFinal);
      writeOutput(response, s);
    } catch (NullPointerException nullPointerException) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
    }
  }

  @GetMapping(value = sCHARGEBOXID)
  public void getDetails(
    @PathVariable("chargeBoxId") String chargeBoxId,
    HttpServletResponse response
  )
    throws IOException {
    List<String> boxList = new ArrayList<>();
    boxList.add(chargeBoxId);
    try {
      ChargePoint.Details cp = chargePointRepository.getDetails(
        chargePointRepository.getChargeBoxIdPkPair(boxList).get(chargeBoxId)
      );

      List<Object> cbDetails = new ArrayList<>();
      cbDetails.add(cp.getChargeBox().getChargeBoxId());
      cbDetails.add(cp.getChargeBox().getChargePointVendor());
      cbDetails.add(cp.getChargeBox().getChargePointModel());
      cbDetails.add(cp.getChargeBox().getChargeBoxSerialNumber());

      cbDetails.add(cp.getChargeBox().getMeterType());
      cbDetails.add(cp.getChargeBox().getMeterSerialNumber());

      cbDetails.add(cp.getChargeBox().getNote());
      cbDetails.add(cp.getChargeBox().getDescription());

      cbDetails.add(cp.getChargeBox().getLocationLatitude());
      cbDetails.add(cp.getChargeBox().getLocationLongitude());

      // AddressRecord addressRecord = cp.address;
      // String street = addressRecord.getStreet() + " " + addressRecord.getHouseNumber();;
      // String city = addressRecord.getCity();

      // String addd = cp.getChargeBox().toString();
      // String addd = addressRecord.toString();

      // cbDetails.add(addressRecord);
      // cbDetails.add(city);

      // String address = addressRecord.getStreet() + " " + addressRecord.getHouseNumber() + ", "
      //         + addressRecord.getCountry() + " " + addressRecord.getZipCode() + " " + addressRecord.getCity();
      // cbDetails.add(address);

      List<ConnectorStatus> latestList = chargePointRepository.getChargePointConnectorStatus();
      List<ConnectorStatus> filteredList = ConnectorStatusFilter.filterAndPreferZero(
        latestList
      );

      cbDetails.add(
        filteredList
          .stream()
          .parallel()
          .filter(cs -> chargeBoxId.equals(cs.getChargeBoxId()))
          .findAny()
          .orElse(null)
          .getStatus()
      );
      cbDetails.add(cp.getChargeBox().getLastHeartbeatTimestamp().getMillis());

      writeOutput(response, serializeArray(cbDetails));
    } catch (NullPointerException nullPointerException) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
    }
  }

  @GetMapping
  public void getChargepoints(HttpServletResponse response) throws IOException {
    chargePointHelperService.getOcppJsonStatus();
    List<Object> cbDetails = new ArrayList<>();
    chargePointHelperService
      .getOcppJsonStatus()
      .forEach(
        js -> {
          List<String> tmpList = new ArrayList<>();
          tmpList.add(js.getChargeBoxId());
          List<ConnectorStatus> latestList = chargePointRepository.getChargePointConnectorStatus();
          List<ConnectorStatus> filteredList = ConnectorStatusFilter.filterAndPreferZero(
            latestList
          );
          tmpList.add(
            filteredList
              .stream()
              .parallel()
              .filter(cs -> js.getChargeBoxId().equals(cs.getChargeBoxId()))
              .findAny()
              .orElse(null)
              .getStatus()
          );
          cbDetails.add(tmpList);
        }
      );
    String s = serializeArray(cbDetails);
    writeOutput(response, s);
  }

  // @GetMapping("/user_login")
  // public void getUserDetails(
  //   @RequestParam("email") String email,
  //   @RequestParam("id") String id,
  //   HttpServletResponse response
  // )
  //   throws IOException {
  //   Optional<User.Overview> user = userRepository
  //     .getOverview(new UserQueryForm())
  //     .stream()
  //     .parallel()
  //     .filter(usr -> usr.getEmail().equals(email))
  //     .findFirst();
  //   if (user.isPresent() && user.get().getOcppIdTag().equals(id)) {
  //     String s = serializeArray("true");
  //     writeOutput(response, s);
  //   } else {
  //     response.setStatus(HttpServletResponse.SC_FORBIDDEN);
  //     writeOutput(response, serializeArray("false"));
  //   }
  // }

  @PutMapping("/addToken")
  public void putToken(
    @RequestParam(value = "id", required = false) String ocpp_parent,
    @RequestParam("token") String token,
    @RequestParam(
      value = "note",
      required = false,
      defaultValue = " "
    ) String note,
    HttpServletResponse response
  )
    throws IOException {
    OcppTagForm newTag = new OcppTagForm();
    newTag.setIdTag(token);
    if (ocpp_parent != null) {
      newTag.setParentIdTag(ocpp_parent);
    }
    if (note == null) {
      note = "";
    }
    newTag.setNote(note);
    try {
      ocppTagRepository.addOcppTag(newTag);
      writeOutput(response, serializeArray("Ok"));
    } catch (Exception exception) {
      exception.printStackTrace();
      response.setStatus(HttpServletResponse.SC_CONFLICT);
      writeOutput(response, serializeArray("Could not add new token"));
    }
  }

  @GetMapping("/getTokens")
  public void getTokens(
    @RequestParam("id") String ocpp_parent,
    HttpServletResponse response
  )
    throws IOException {
    try {
      List<List<String>> responseList = getTokenList(ocpp_parent);
      writeOutput(response, serializeArray(responseList));
    } catch (NullPointerException nullPointerException) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
    }
  }

  @DeleteMapping("/removeToken")
  public void removeToken(
    @RequestParam("tokenID") String token,
    HttpServletResponse response
  )
    throws IOException {
    Optional<OcppTag.Overview> ocppTag = ocppTagRepository
      .getOverview(new OcppTagQueryForm())
      .stream()
      .filter(o -> o.getIdTag().equals(token))
      .findFirst();
    // Only delete non parent ID tags
    if (ocppTag.isPresent() && ocppTag.get().getParentOcppTagPk() != null) {
      int ocppTagPk = ocppTag.get().getOcppTagPk();
      ocppTagRepository.deleteOcppTag(ocppTagPk);
      writeOutput(response, serializeArray("Ok, deleted."));
    } else {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      writeOutput(response, serializeArray("Can't delete token."));
    }
  }

  @GetMapping("/getStatistics")
  public void getStatistics(
    @RequestParam("tokenID") String token,
    @RequestParam("period") TransactionQueryForm.QueryPeriodType period,
    @RequestParam(
      value = "allStatistics",
      required = false,
      defaultValue = "false"
    ) boolean allStatistics,
    HttpServletResponse response
  )
    throws IOException {
    try {
      TransactionQueryForm params = new TransactionQueryForm();
      params.setPeriodType(period);
      params.setType(TransactionQueryForm.QueryType.ALL);
      List<String> ocppTagList = new ArrayList<>();
      if (allStatistics) {
        if (ocppTagRepository.getParentIdtag(token) != null) {
          token = ocppTagRepository.getParentIdtag(token);
        }
        // Get all Transactions of the token
        // First get all Tags
        String finalToken = token;
        ocppTagList =
          ocppTagRepository
            .getIdTags()
            .stream()
            .filter(
              tag ->
                Objects.equals(
                  ocppTagRepository.getParentIdtag(tag),
                  finalToken
                )
            )
            .collect(Collectors.toList());
      }
      if (!ocppTagList.contains(token)) {
        ocppTagList.add(0, token);
      }
      Map<Integer, List<String>> transactionMap = new LinkedHashMap<>();
      for (String tag : ocppTagList) {
        params.setOcppIdTag(tag);
        Map<Integer, List<String>> finalTransactionMap = transactionMap;
        transactionRepository
          .getTransactions(params)
          .stream()
          .parallel()
          .filter(
            transaction ->
              transaction.getStopTimestamp() != null &&
              !transaction.getStopTimestamp().isEmpty()
          )
          .forEach(
            transaction -> {
              List<String> transactionDetailList = new ArrayList<>();
              transactionDetailList.add(String.valueOf(transaction.getId()));
              transactionDetailList.add(transaction.getChargeBoxId());
              AddressRecord addressRecord = chargePointRepository
                .getDetails(transaction.getChargeBoxPk())
                .getAddress();
              String temp = addressRecord.getStreet();
              String street = "";
              if (temp != null) street = temp;
              temp = addressRecord.getHouseNumber();
              if (temp != null) street = street + " " + temp;
              String country = addressRecord.getCountry();
              String zipCode = addressRecord.getZipCode();
              String city = addressRecord.getCity();
              transactionDetailList.add(street);
              transactionDetailList.add(country);
              transactionDetailList.add(zipCode);
              transactionDetailList.add(city);
              // String address = addressRecord.getStreet() + " " + addressRecord.getHouseNumber() + ", "
              //         + addressRecord.getCountry() + " " + addressRecord.getZipCode() + " " + addressRecord.getCity();
              // transactionDetailList.add(address);
              transactionDetailList.add(transaction.getOcppIdTag());
              transactionDetailList.add(transaction.getStartValue());
              transactionDetailList.add(
                transaction.getStartTimestampDT().toString()
              );
              transactionDetailList.add(
                transaction.getStopTimestampDT().toString()
              );
              transactionDetailList.add(transaction.getStopReason());
              transactionDetailList.add(
                transaction.getStopEventActor().toString()
              );
              transactionDetailList.add(transaction.getStopValue());
              transactionRepository
                .getDetails(transaction.getId())
                .getValues()
                .stream()
                .parallel()
                .filter(
                  meterValues ->
                    meterValues.getUnit() != null &&
                    !meterValues.getUnit().isEmpty()
                )
                .findFirst()
                .ifPresentOrElse(
                  (
                    meterValues ->
                      transactionDetailList.add(meterValues.getUnit())
                  ),
                  () -> transactionDetailList.add("")
                );
              finalTransactionMap.put(
                transaction.getId(),
                transactionDetailList
              );
            }
          );
      }
      transactionMap =
        transactionMap
          .entrySet()
          .stream()
          .parallel()
          .sorted(Collections.reverseOrder(Map.Entry.comparingByKey()))
          .collect(
            Collectors.toMap(
              Map.Entry::getKey,
              Map.Entry::getValue,
              (e1, e2) -> e2,
              LinkedHashMap::new
            )
          );
      writeOutput(response, serializeArray(transactionMap));
    } catch (NullPointerException nullPointerException) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
    } catch (Exception illegalArgumentException) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }
  }

  @RequestMapping(method = RequestMethod.OPTIONS, value = "/**")
  public void manageOptions(HttpServletResponse response) throws IOException {
    writeOutput(response, "");
  }

  private List<List<String>> getTokenList(String ocpp_parent)
    throws NullPointerException {
    List<String> ocppTagList = ocppTagRepository
      .getIdTags()
      .stream()
      .filter(
        tag ->
          Objects.equals(ocppTagRepository.getParentIdtag(tag), ocpp_parent)
      )
      .collect(Collectors.toList());
    if (!ocppTagList.contains(ocpp_parent)) {
      ocppTagList.add(0, ocpp_parent);
    }
    List<List<String>> responseList = new ArrayList<>();

    ocppTagList.forEach(
      tag -> {
        OcppTagQueryForm ocppTagQueryForm = new OcppTagQueryForm();
        ocppTagQueryForm.setIdTag(tag);
        String note;
        Optional<OcppTag.Overview> optionalOverview = ocppTagRepository
          .getOverview(ocppTagQueryForm)
          .stream()
          .findFirst();
        if (optionalOverview.isPresent()) {
          note =
            ocppTagRepository
              .getRecord(optionalOverview.get().getOcppTagPk())
              .getNote();
          if (note == null) {
            note = "";
          }
          responseList.add(Stream.of(tag, note).collect(Collectors.toList()));
        } else {
          throw new NullPointerException();
        }
      }
    );
    return responseList;
  }

  private String serializeArray(Object object) {
    try {
      return objectMapper.writeValueAsString(object);
    } catch (JsonProcessingException e) {
      // As fallback return empty array, do not let the frontend hang
      log.error(
        "Error occurred during serialization of response. Returning empty array instead!",
        e
      );
      return "[]";
    }
  }

  /**
   * Restituisce l'indirizzo sotto forma di Map di String.
   */
  private Map<String, String> getAddress(ChargePoint.Details cp) {
    Map<String, String> address = new HashMap<String, String>();

    // cp.getAddress()
    /*
      +--+--+--+--+--+--+\n|address_pk|street|house_number|zip_code|city|
      country|\n+--+--+--+--+--+--+\n|2|PIAZZALE GIOVANNI DALLE BANDE NERE 9|
      {null}|{null}|MILANO|{null}|\n+--+--+---+--+--+--+\n
    */

    String indirizzo = String.valueOf(cp.getAddress());

    if (indirizzo == null) return null;
    if (indirizzo == "null") return null;

    for (int i = 0; i < 9; i++) {
      indirizzo =
        indirizzo.substring(indirizzo.indexOf("|") + 1, indirizzo.length());
    }

    address.put("via", returnCleanElement(indirizzo, 0));
    address.put("numero_civico", returnCleanElement(indirizzo, 1));
    address.put("cap", returnCleanElement(indirizzo, 2));
    address.put("citta", returnCleanElement(indirizzo, 3));
    address.put("nazione", returnCleanElement(indirizzo, 4));

    return address;
  }

  /**
   * questa funzione viene usata per fare una substring dell'indirizzo e
   * ottenere il parametro che si vuole (città, via, cap, ...) in base
   * all'indice (0 -> Via, 1 -> Numero civico, 2 -> Cap, 3 -> Città,
   * 4 -> Nazione)
   */
  private String returnCleanElement(String stringToClean, int index) {
    if (stringToClean == null) return null;
    if (stringToClean == "null") return null;

    for (int i = 0; i < index; i++) {
      stringToClean =
        stringToClean.substring(
          stringToClean.indexOf("|") + 1,
          stringToClean.length()
        );
    }

    String cleanedElement = stringToClean.substring(
      0,
      stringToClean.indexOf("|")
    );

    cleanedElement = cleanedElement.trim();

    if (cleanedElement.contains("{null}")) {
      cleanedElement = "null";
    }

    return cleanedElement;
  }

  /**
   * We want to handle this JSON conversion locally, and do not want to register an application-wide
   * HttpMessageConverter just for this little class. Otherwise, it might have unwanted side effects due to
   * different serialization/deserialization needs of different APIs.
   * <p>
   * That's why we are directly accessing the low-level HttpServletResponse and manually writing to output.
   */
  private void writeOutput(HttpServletResponse response, String str)
    throws IOException {
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setHeader("Access-Control-Allow-Origin", "*");
    response.setHeader(
      "Access-Control-Allow-Methods",
      "POST, GET, PUT, DELETE, OPTIONS"
    );
    response.setHeader("Access-Control-Allow-Headers", "Content-Type");
    response.getWriter().write(str);
  }
}
