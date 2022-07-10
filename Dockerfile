FROM amazoncorretto:8

#RUN curl -L https://www.scala-sbt.org/sbt-rpm.repo > sbt-rpm.repo && \
#    mv sbt-rpm.repo /etc/yum.repos.d/ && \
#    yum install -y sbt git 

WORKDIR /blackboard
ENV PATH="/blackboard/bin:${PATH}"
ADD blackboard.tar.gz /
ENTRYPOINT ["blackboard", "-J-Xmx8g", "-Dplay.http.secret.key=0xdeadbeef"]
