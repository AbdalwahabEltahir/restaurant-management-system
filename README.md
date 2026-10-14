# Restaurant Management System

A simple full-stack restaurant management app built with Java and Spring Boot. It supports guest menu browsing, staff order management, table-based service flow, loyalty points, and a small chatbot for menu questions.

## Overview

This project is a demo restaurant system designed to simulate how a restaurant can manage:

- menu items
- guest tables
- staff order handling
- payment and loyalty tracking
- order status updates

The application uses a MongoDB database and serves a lightweight frontend from the static resources folder.

## Features

- Guest menu page for each table
- QR-code-based table identification
- Staff dashboard for managing orders
- Order status flow: pending → preparing → ready → served/cancelled
- Admin menu management
- Customer loyalty points and payment tracking
- Chatbot-style assistance for food questions

## Tech Stack

- Java 17
- Spring Boot 4.1.1
- Spring Web MVC
- Spring Data MongoDB
- Maven
- HTML, CSS, and JavaScript

## Project Structure

```text
restaurant-management-system/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/example/demo/
│   │   │       ├── controller/
│   │   │       ├── model/
│   │   │       ├── repository/
│   │   │       └── service/
│   │   └── resources/
│   │       ├── application.properties
│   │       └── static/
│   └── test/
│       └── java/
├── pom.xml
├── mvnw
├── mvnw.cmd
└── README.md
```

## Prerequisites

Before running the project, make sure you have:

- Java 17+
- Maven or the included Maven wrapper
- MongoDB installed and running locally

This project is configured to use MongoDB at:

```properties
spring.data.mongodb.uri=mongodb://localhost:27017/restaurant
```

## Run the Application

Open a terminal in the project root and run:

```bash
./mvnw spring-boot:run
```

On Windows PowerShell:

```powershell
./mvnw.cmd spring-boot:run
```

Then open:

```text
http://localhost:8080
```

## Access the App

### Guest view

The guest experience is tied to a table code or QR value.

Example:

```text
http://localhost:8080/?t=TABLE_CODE
```

If no valid table code is provided, the app redirects to the staff login page.

### Staff/Admin view

```text
http://localhost:8080/staff.html
```

Default demo accounts created at startup:

- Admin: `admin` / `admin123`
- Staff: `staff` / `staff123`

## Main Functionality

### Guest flow

- View the available menu for a table
- See current orders for that table
- Ask the chatbot questions about food, ingredients, and allergies
- Link a phone number to the table for loyalty tracking

### Staff flow

- Open the kitchen/order queue
- Update the status of orders
- Process payment for a table
- Manage menu availability and admin menu items

### Admin flow

- Add, update, disable, or view menu items
- Manage item availability without deleting records completely

## API Quick Reference

### Menu

- `GET /api/menu?t=CODE` — fetch available menu items for a table
- `GET /api/admin/menu-items` — list all menu items
- `POST /api/admin/menu-items` — create a menu item
- `PUT /api/admin/menu-items/{id}` — update a menu item
- `PATCH /api/admin/menu-items/{id}/available` — enable/disable an item

### Orders

- `GET /api/orders/table/{qrCodeHash}` — get open orders for a table
- `POST /api/staff/orders/table/{qrCodeHash}` — create an order for a table
- `GET /api/staff/orders` — view all open orders
- `PATCH /api/staff/orders/{id}/status` — change order status
- `POST /api/staff/orders/table/{qrCodeHash}/pay` — complete payment

## Default Data

When the app starts with an empty database, it creates:

- a default admin account
- a default staff account
- a sample menu
- several restaurant tables with random QR codes

## Useful Commands

```bash
./mvnw clean test
./mvnw package
```

## Notes

- The database name is `restaurant`.
- This project is intended as a demo or learning application rather than a production-grade restaurant platform.
- Guest access depends on a valid table/QR code, which is how the app identifies the customer’s dining table.

## License

This project is provided for educational and local development use.
