package com.example.demo.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("tables")
public class RestaurantTable {

    public enum Status {
        AVAILABLE,
        OCCUPIED
    }

    @Id
    private String id;

    private int tableNumber;

    /** Random code inside the QR link, so guests cannot guess other tables. */
    @Indexed(unique = true)
    private String qrCodeHash;

    private Status status = Status.AVAILABLE;

    public RestaurantTable() {
    }

    public RestaurantTable(int tableNumber, String qrCodeHash) {
        this.tableNumber = tableNumber;
        this.qrCodeHash = qrCodeHash;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public int getTableNumber() { return tableNumber; }
    public void setTableNumber(int tableNumber) { this.tableNumber = tableNumber; }

    public String getQrCodeHash() { return qrCodeHash; }
    public void setQrCodeHash(String qrCodeHash) { this.qrCodeHash = qrCodeHash; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
}