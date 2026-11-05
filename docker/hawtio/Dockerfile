FROM tomcat:10.1-jdk17-temurin
ADD --checksum=sha256:87595711b103e705a22ea94f5b4fe4aa1293bb2a8357c446681222d8ae7938b0 https://repo.maven.apache.org/maven2/io/hawt/hawtio-default/5.3.0/hawtio-default-5.3.0.war /usr/local/tomcat/webapps/hawtio.war
# Development console only. The fixed-destination adapter authenticates every
# management call; Gateway authentication and CSRF remain enabled behind it.
ENV CATALINA_OPTS="-Dhawtio.authenticationEnabled=false -Dhawtio.proxyAllowlist=hawtio-proxy"
