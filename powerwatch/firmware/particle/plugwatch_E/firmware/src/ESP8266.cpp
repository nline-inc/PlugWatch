#include "Serial5/Serial5.h"

#include "ESP8266.h"

const int WIFI_PWR_EN = B1;
const String endstring = "OK\r\n";

ESP8266::ESP8266() {
  // Power cycle and reset
  pinMode(WIFI_PWR_EN, OUTPUT);

  powerOff();

  response = "";
}

void ESP8266::powerOff() {
  digitalWrite(WIFI_PWR_EN, LOW);
  delay(1000);
}

void ESP8266::powerOn() {
  digitalWrite(WIFI_PWR_EN, HIGH);
  delay(1000);

  // Reset and set baud rate to 9600 if at 115200
  Serial5.end();
  Serial5.begin(115200);
  Serial5.println("AT+RST");
  delay(1000);
  Serial5.println("AT+UART_CUR=9600,8,1,0,0");
  delay(1000);

  // Set baud to 9600
  Serial5.end();
  Serial5.begin(9600);

  // Set mode to client
  Serial5.println("AT+CWMODE=1");
  delay(1000);

  Serial5.println("AT");
  delay(1000);

  //Clear out response from buffer
  while(Serial5.available()) {
    Serial5.read();
  }
}

void ESP8266::beginScan() {
  response = "";
  start_time = millis();
  Serial5.println("AT+CWLAP");
}

String ESP8266::getResult() {
  return response;
}

LoopStatus ESP8266::loop() {
  if(millis() - start_time < 10000) {
    if (Serial5.available()) {
        String recv = Serial5.readString();
        response.concat(recv);
        if (recv.endsWith(endstring)) {
          return FinishedSuccess;
        } else {
          return NotFinished;
        }
    } else {
      return NotFinished;
    }
  } else {
    return FinishedError;
  }
}
