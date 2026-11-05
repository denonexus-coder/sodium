# Integração — AI Memory Engine V3 + Sodium (Minecraft Fabric)

Este documento descreve como o AI Memory Engine V3 foi integrado ao projeto
Sodium localizado em:

```
/data/data/com.termux/files/usr/var/lib/proot-distro/containers/ubuntu/rootfs/root/Mobrender/sodium
```

---

## O que foi adicionado

### 1. Motor principal
**`src/main/java/me/jellysquid/mods/sodium/memory/AIMemoryEngineV3.java`**

Contém toda a lógica de:
- Monitorização contínua do heap da JVM (thread própria, não bloqueante)
- Classificação de pressão: NORMAL (<65%), MODERADO (65-75%), ELEVADO (75-85%), CRÍTICO (>85%)
- Predição de pressão futura com taxa de alocação EWMA (KB/s)
- Object Pool (reuso de `byte[]` e outros buffers — estilo FerriteCore/FoamFix)
- `LongLongMap` — mapa primitivo sem boxing (evita pressão do GC)
- Cache TTL/LRU com expiração automática e remoção cautelar (1/3 por ciclo)
- Referências temporárias com liberação segura
- Estatísticas de GC (leitura apenas — nunca `System.gc()`)
- Heartbeat para medição de latência na thread principal

### 2. Inicialização no cliente
**`src/main/java/me/jellysquid/mods/sodium/client/SodiumClientMod.java`**

Adicionada chamada a `AIMemoryEngineV3.start()` dentro de `onInitializeClient()`.
O motor inicia automaticamente quando o Sodium carrega no cliente.

### 3. Mixins (hooks de bytecode)

| Mixin | Classe alvo | O que faz |
|-------|-------------|-----------|
| `memory.ChunkLoadMixin` | `net.minecraft.world.chunk.Chunk` | Registra ~64 KB de alocação por chunk construído |
| `memory.BufferBuilderAllocMixin` | `net.minecraft.client.render.BufferBuilder` | Registra ~8 KB por chamada de `begin()` (buffers de vértice) |
| `memory.RenderLoopHeartbeatMixin` | `net.minecraft.client.MinecraftClient` | Roda `heartbeat()` a cada frame e `monitorCycle()` a cada ~60 frames |

### 4. Registro de Mixins
**`src/main/resources/sodium.mixins.json`**

Adicionadas 3 novas entradas na seção `"client"`:
```json
"memory.ChunkLoadMixin",
"memory.BufferBuilderAllocMixin",
"memory.RenderLoopHeartbeatMixin"
```

---

## Como compilar no Termux / Ubuntu (proot)

### Pré-requisitos
- Java 21 JDK instalado (`pkg install openjdk-21` no Termux)
- proot-distro com Ubuntu configurado
- Acesso à pasta do projeto

### Comandos de build

```bash
# Entrar no container Ubuntu (se ainda não estiver)
proot-distro login ubuntu

# Navegar até o projeto
cd /root/Mobrender/sodium

# Compilar com Gradle (usa o wrapper incluído)
./gradlew build

# Ou apenas compilar as classes (mais rápido para testes)
./gradlew compileJava
```

O build gera o JAR em:
```
build/libs/sodium-fabric-0.5.13+mc1.20.1.jar
```

### Parâmetros JVM recomendados ao executar o Minecraft com este Sodium modificado

```bash
-Xms128m -Xmx512m \
-XX:+UseG1GC \
-XX:MaxGCPauseMillis=50 \
-XX:ConcGCThreads=2 \
-XX:ParallelGCThreads=4 \
-XX:InitiatingOccupancyFraction=45
```

---

## API pública para uso em outros Mixins

Se quiser adicionar mais hooks personalizados em outros mods ou classes:

```java
// Registrar uma alocação manualmente (chamar após new byte[] ou similar)
AIMemoryEngineV3.mixinRecordAlloc(numeroDeBytes);

// Adquirir objeto do pool (reuso — evita alocação nova)
byte[] buf = AIMemoryEngineV3.mixinAcquire(byte[].class, () -> new byte[8192]);

// Devolver objeto ao pool após uso
AIMemoryEngineV3.mixinRelease(byte[].class, buf);

// Verificar se a pressão está crítica antes de operação pesada
if (AIMemoryEngineV3.mixinIsCritical()) {
    // adiar ou cancelar operação intensiva
}

// Obter pressão atual (0.0 a 100.0)
double pct = AIMemoryEngineV3.mixinPressurePct();
```

---

## Regras respeitadas (conforme especificação original)

1. ✅ Zero chamadas a `System.gc()`
2. ✅ Nenhum objeto em uso é eliminado
3. ✅ Sem limpeza direta da memória JVM (apenas caches e refs temporárias)
4. ✅ Thread de monitorização independente (não bloqueia a thread principal do Minecraft)
5. ✅ Thresholds configuráveis (65% / 75% / 85%)
6. ✅ Java 21 compatível (o Sodium usa sourceCompatibility 17, que roda em Java 21)
7. ✅ Sem dependências externas adicionais
8. ✅ Limite absoluto de 30 segundos nos testes
9. ✅ Não altera parâmetros da JVM automaticamente — apenas lê e recomenda

---

## Relatórios

O motor escreve relatórios no log do SLF4J (mesmo canal do Sodium). Para forçar um relatório manualmente:

```java
AIMemoryEngineV3.printReport();
```

Os relatórios incluem: heap usada, pressão máxima, taxa de alocação, previsão, hits/misses do pool, entradas de cache, ciclos GC, pausa máxima do GC, heartbeat máximo.

---

## Estrutura final de ficheiros alterados/adicionados

```
src/main/java/me/jellysquid/mods/sodium/memory/AIMemoryEngineV3.java      [NOVO]
src/main/java/me/jellysquid/mods/sodium/client/SodiumClientMod.java     [EDITADO]
src/main/java/me/jellysquid/mods/sodium/mixin/memory/ChunkLoadMixin.java [NOVO]
src/main/java/me/jellysquid/mods/sodium/mixin/memory/BufferBuilderAllocMixin.java [NOVO]
src/main/java/me/jellysquid/mods/sodium/mixin/memory/RenderLoopHeartbeatMixin.java [NOVO]
src/main/resources/sodium.mixins.json                                    [EDITADO]
```

---

## Integração com outros mods

Como o engine expõe métodos estáticos públicos, qualquer outro mod no mesmo
classpath pode chamar `AIMemoryEngineV3.mixin*` diretamente, sem necessidade
de depender do Sodium como biblioteca. Basta garantir que a classe esteja
disponível no classpath em tempo de execução.
