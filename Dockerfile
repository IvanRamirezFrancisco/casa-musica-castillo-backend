# ==========================================
# DOCKERFILE PARA PRODUCCIÓN - SPRING BOOT
# Casa de Música Castillo - Auth System
# ==========================================

# Etapa 1: Build
FROM maven:3.9.6-eclipse-temurin-21-alpine AS build

WORKDIR /app

# Copiar archivos de dependencias primero (cache de Docker)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copiar código fuente y compilar
COPY src ./src
RUN mvn clean package -DskipTests -B

# Etapa 2: Runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Crear usuario no-root para seguridad
RUN apk add --no-cache postgresql18-client && \
    addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup && \
    mkdir -p /app/uploads && chown appuser:appgroup /app/uploads

# Copiar JAR desde etapa de build
COPY --from=build /app/target/*.jar app.jar

# Cambiar a usuario no-root
USER appuser

# Deja margen para metaspace, código compilado, pilas y memoria del contenedor.
ENV JAVA_OPTS="-Xms64m -Xmx224m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m"
ENV SPRING_PROFILES_ACTIVE=production

# Puerto de la aplicación
EXPOSE 8080

# AJUSTE 2: Healthcheck simplificado o comentado
# Si no tienes Actuator instalado, esto matará tu app.
# Lo dejo comentado por seguridad. Descoméntalo solo si sabes que /actuator/health funciona.
# HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=5 \
#    CMD wget --no-verbose --tries=1 --spider http://localhost:${PORT:-8080}/actuator/health || exit 1

# Comando de inicio - Render proporciona la variable $PORT
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar app.jar"]
