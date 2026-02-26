FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY GraphDFSServer.java .

RUN javac GraphDFSServer.java

ENV PORT=8080
EXPOSE 8080

CMD ["sh", "-c", "java GraphDFSServer"]
