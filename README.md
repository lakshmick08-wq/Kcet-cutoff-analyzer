# 🎓 KCET Cutoff Analyzer

A full-stack web application designed to help students analyze KCET engineering college cutoff PDFs and quickly find colleges that match their KCET rank, preferred branch, and reservation category.

The application extracts data from official KCET cutoff PDFs, stores it efficiently, and provides an easy-to-use interface for searching and filtering colleges based on eligibility.

---

## ✨ Features

- 📄 Upload official KCET cutoff PDF files
- 🔍 Automatically extract cutoff data from PDF content
- 🏫 Search colleges based on KCET rank
- 🎯 Filter results by preferred engineering branch
- 👥 Support for multiple reservation categories including:
  - GM
  - 2A
  - 2B
  - 3A
  - 3B
  - SC
  - ST
  - EWS
  - Kannada Medium (KM)
  - Rural
  - Other applicable KEA categories
- ⚡ Fast and intuitive search experience
- 📱 Responsive user interface
- ❌ Input validation and error handling
- 🧱 Clean MVC-style architecture using Spring Boot

---

## 🛠 Tech Stack

| Technology | Purpose |
|------------|---------|
| Java 17 | Backend development |
| Spring Boot | REST API and application framework |
| Maven | Dependency management and build |
| HTML5 | Frontend structure |
| CSS3 | Styling and layout |
| JavaScript | Client-side interactivity |
| Apache PDFBox | PDF text extraction |
| REST API | Client-server communication |

---

## 📂 Project Structure

```text
KCET-Cutoff-Analyzer
│
├── frontend
│   ├── index.html
│   ├── style.css
│   └── script.js
│
├── src
│   ├── main
│   │   ├── java
│   │   │   └── com.kcet.analyzer
│   │   │       ├── controller
│   │   │       ├── service
│   │   │       ├── repository
│   │   │       ├── model
│   │   │       ├── util
│   │   │       └── KcetAnalyzerApplication.java
│   │   └── resources
│   │       └── application.properties
│
├── uploads
├── pom.xml
└── README.md
```

---

## 🚀 How It Works

1. A student uploads an official KCET cutoff PDF.
2. The backend extracts text using Apache PDFBox.
3. Cutoff data is parsed and stored for later search.
4. The user enters:
   - KCET rank
   - preferred branch
   - reservation category
5. The application displays all eligible colleges matching the input criteria.

---

## 📋 Supported Search Filters

- KCET Rank
- Engineering Branch
- Reservation Category
- College Name (planned)
- District (planned)

---

## 📌 Reservation Categories

| Category |
|----------|
| GM |
| 2A |
| 2B |
| 3A |
| 3B |
| SC |
| ST |
| EWS |
| Kannada Medium |
| Rural |
| Other KEA Categories |

---

## ⚙ Installation

### Clone the repository

```bash
git clone https://github.com/lakshmick08-wq/kcet-cutoff-analyzer.git
cd kcet-cutoff-analyzer
```

### Build the project

```bash
mvn clean install
```

### Run the Spring Boot application

```bash
mvn spring-boot:run
```

The application will start at:

```text
http://localhost:8080
```

---

## 🌐 API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | /upload | Upload a KCET PDF file |
| GET | /search | Search colleges based on filters |
| GET | /branches | Get available branches |
| GET | /categories | Get reservation categories |

---

## 📄 Sample Workflow

### Upload PDF

✔ Choose an official KCET cutoff PDF

### Extract Data

✔ PDF content is parsed automatically

### Enter Details

- KCET Rank
- Branch
- Category

### View Results

✔ Eligible colleges are displayed instantly

---

## 🖥 User Interface

### Home Page

- Upload PDF
- Enter rank
- Select branch
- Select category

### Search Results

Displays:

- College Name
- Branch
- Category
- Cutoff Rank

---

## 📈 Future Enhancements

- 📊 College comparison
- ❤️ Favorite colleges
- 📍 District-wise search
- 📉 Previous-year trend analysis
- 🤖 AI-based college recommendation
- 📱 Mobile responsiveness improvements
- ☁ Database integration (MySQL)
- 🔐 User authentication

---

## 💡 Why This Project?

Finding suitable colleges manually from hundreds of pages of KCET cutoff PDFs is time-consuming and overwhelming.

This application simplifies the process by allowing students to upload the official cutoff PDF and instantly discover colleges matching their rank, preferred branch, and reservation category.

---

## 👩‍💻 Author

**Lakshmi C Koujalagi**

- GitHub: https://github.com/lakshmick08-wq
- LinkedIn: https://linkedin.com/in/lakshmi-c-koujalagi

---

## 📜 License

This project is developed for educational purposes.

Feel free to use and improve it.

---

## ⭐ If you found this project useful, don’t forget to star the repository!
