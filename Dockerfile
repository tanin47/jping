FROM ibm-semeru-runtimes:open-25-jdk

WORKDIR /app

COPY ./build/staging-deploy/io/github/tanin47/jping/*/jping-*[0-9].jar .

EXPOSE 9090

CMD ["sh", "-c", "exec java -jar jping-*.jar"]
