# Dockerfile dùng OpenJDK 21 (tag ổn định trên Docker Hub)
FROM openjdk:21-slim

WORKDIR /app

# copy source
COPY GraphDFSServer.java /app/

# compile
RUN javac -d . GraphDFSServer.java

# Render sẽ set PORT env var; expose cho minh chứng
ENV PORT 8080
EXPOSE 8080

# run
CMD ["sh", "-c", "java -cp . GraphDFSServer"]
