# Dockerfile dùng JDK 21
FROM eclipse-temurin:21-jdk-focal

WORKDIR /app

# copy source
COPY GraphDFSServer.java /app/

# compile (javac từ JDK 21)
RUN javac -d . GraphDFSServer.java

# Documented port; Render sẽ set PORT env var
ENV PORT 8080
EXPOSE 8080

# chạy server (GraphDFSServer đọc System.getenv("PORT"))
CMD ["sh", "-c", "java -cp . GraphDFSServer"]