FROM docker.elastic.co/elasticsearch/elasticsearch:8.17.10

RUN elasticsearch-plugin install --batch analysis-smartcn

ENV ES_JAVA_OPTS="-Xms512m -Xmx1024m"
ENV discovery.type="single-node"
ENV xpack.security.enabled="false"
ENV xpack.security.enrollment.enabled="false"
ENV xpack.security.http.ssl.enabled="false"
ENV xpack.security.transport.ssl.enabled="false"

HEALTHCHECK --interval=10s --timeout=10s --retries=6 \
  CMD curl -sf http://localhost:9200/_cluster/health || exit 1