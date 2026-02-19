---
name: qa-jacoco-coverage
description: JaCoCo code coverage for functional API tests. Maven-first approach that works behind corporate firewalls. Covers Maven plugin setup, Spring Boot instrumentation, test execution, coverage dump, and HTML report generation.
---

# JaCoCo Coverage — Corporate-Friendly Maven Approach

## Strategy: Use Maven for EVERYTHING (No curl/wget Downloads)

Corporate firewalls block direct downloads from Maven Central.
Maven downloads JaCoCo through corporate Artifactory/Nexus — no firewall issues.

## DECISION TREE
```
User requests JaCoCo
  ├─ Dev project in workspace?  NO → "Add dev project or AI-estimate"
  ├─ Dev project builds?        NO → "Fix build first"
  ├─ Test suite exists?         NO → "Create tests first (option 7 or 1)"
  ├─ Can modify dev pom.xml?    YES → METHOD 1 (Recommended)
  │                             NO → METHOD 2 (Standalone downloader)
  └─ FALLBACK: AI-Estimated Coverage (with disclaimer)
```

---

## METHOD 1: MAVEN PLUGIN (Recommended)

### Step 1: Add to Dev pom.xml `<build><plugins>`
```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.12</version>
    <executions>
        <execution>
            <id>prepare-agent</id>
            <goals><goal>prepare-agent</goal></goals>
            <configuration>
                <o>tcpserver</o>
                <address>*</address>
                <port>6300</port>
                <includes><include>com/fmr/fi/analytics/**</include></includes>
            </configuration>
        </execution>
    </executions>
</plugin>
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-dependency-plugin</artifactId>
    <version>3.6.1</version>
    <executions>
        <execution>
            <id>download-jacoco-tools</id>
            <phase>package</phase>
            <goals><goal>copy</goal></goals>
            <configuration><artifactItems>
                <artifactItem><groupId>org.jacoco</groupId><artifactId>org.jacoco.cli</artifactId>
                    <version>0.8.12</version><classifier>nodeps</classifier>
                    <outputDirectory>${project.build.directory}/jacoco-tools</outputDirectory>
                    <destFileName>jacoco-cli.jar</destFileName></artifactItem>
                <artifactItem><groupId>org.jacoco</groupId><artifactId>org.jacoco.agent</artifactId>
                    <version>0.8.12</version><classifier>runtime</classifier>
                    <outputDirectory>${project.build.directory}/jacoco-tools</outputDirectory>
                    <destFileName>jacoco-agent.jar</destFileName></artifactItem>
            </artifactItems></configuration>
        </execution>
    </executions>
</plugin>
```

### Step 2: Build (Downloads JaCoCo via Corporate Proxy)
```bash
cd {dev-project}/ && mvn clean package -DskipTests
ls target/jacoco-tools/   # ✅ jacoco-agent.jar + jacoco-cli.jar
```

### Step 3: Stop Existing API
```bash
lsof -ti:8080 | xargs kill -9 2>/dev/null; sleep 2
```

### Step 4: Start API with JaCoCo Agent
```bash
cd {dev-project}/
APP_JAR=$(ls target/*.jar | grep -v original | grep -v jacoco | head -1)
java -javaagent:target/jacoco-tools/jacoco-agent.jar=output=tcpserver,address=*,port=6300,includes=com.fmr.fi.analytics.* \
  -jar ${APP_JAR} &
DEV_PID=$!
# Wait for startup
for i in $(seq 1 90); do
  curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1 && echo "✅ API UP with JaCoCo" && break
  [ $i -eq 90 ] && echo "❌ Failed" && kill $DEV_PID; sleep 1
done
```

### Step 5: Run Tests
```bash
cd {test-project}/
mvn clean test -Dkarate.env=local 2>&1 | tee jacoco-test-run.log
```

### Step 6: Dump Coverage
```bash
cd {dev-project}/
java -jar target/jacoco-tools/jacoco-cli.jar dump \
  --address localhost --port 6300 --destfile target/coverage-functional.exec
```

### Step 7: Generate Report
```bash
java -jar target/jacoco-tools/jacoco-cli.jar report target/coverage-functional.exec \
  --classfiles target/classes --sourcefiles src/main/java \
  --html target/coverage-report --csv target/coverage-report/coverage.csv \
  --xml target/coverage-report/coverage.xml --name "Functional Test Coverage"
echo "📊 Report: target/coverage-report/index.html"
```

### Step 8: Display Summary
```bash
echo "━━━━ COVERAGE ━━━━"
tail -n +2 target/coverage-report/coverage.csv | awk -F',' '
  {tim+=$4;tic+=$5;tlm+=$8;tlc+=$9;tbm+=$6;tbc+=$7}
  END{
    if(tim+tic>0) printf "  Instructions: %d%%\n",tic*100/(tim+tic)
    if(tlm+tlc>0) printf "  Lines:        %d%%\n",tlc*100/(tlm+tlc)
    if(tbm+tbc>0) printf "  Branches:     %d%%\n",tbc*100/(tbm+tbc)
  }'
```

### Step 9: Cleanup + Gap Analysis
```bash
kill $DEV_PID 2>/dev/null
echo "Revert pom: git checkout {dev-project}/pom.xml"
```
Read CSV, find classes <50%, suggest specific test scenarios.

---

## METHOD 2: STANDALONE DOWNLOADER (Dev POM Cannot Be Modified)

```bash
mkdir -p {workspace}/jacoco-downloader
cat > {workspace}/jacoco-downloader/pom.xml << 'EOF'
<project><modelVersion>4.0.0</modelVersion>
  <groupId>tools</groupId><artifactId>jacoco-dl</artifactId><version>1.0</version>
  <build><plugins><plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-dependency-plugin</artifactId><version>3.6.1</version>
    <executions><execution><phase>package</phase><goals><goal>copy</goal></goals>
      <configuration><artifactItems>
        <artifactItem><groupId>org.jacoco</groupId><artifactId>org.jacoco.agent</artifactId>
          <version>0.8.12</version><classifier>runtime</classifier>
          <outputDirectory>${project.basedir}/../jacoco-tools</outputDirectory>
          <destFileName>jacoco-agent.jar</destFileName></artifactItem>
        <artifactItem><groupId>org.jacoco</groupId><artifactId>org.jacoco.cli</artifactId>
          <version>0.8.12</version><classifier>nodeps</classifier>
          <outputDirectory>${project.basedir}/../jacoco-tools</outputDirectory>
          <destFileName>jacoco-cli.jar</destFileName></artifactItem>
      </artifactItems></configuration>
    </execution></executions>
  </plugin></plugins></build>
</project>
EOF
cd {workspace}/jacoco-downloader && mvn clean package -q
ls {workspace}/jacoco-tools/   # Then use in Steps 4-8
```

---

## AI-ESTIMATED COVERAGE (When JaCoCo Not Possible)
```
⚠️ DISCLAIMER: AI-estimated only. Not actual instrumented coverage.
| Endpoint | Tests | Est.% | Gaps |
|----------|-------|-------|------|
| POST /customers | 8 | ~65% | validation, auth edge |
⚠️ For accurate: run JaCoCo with dev project locally (option 8).
```