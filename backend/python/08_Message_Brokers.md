# 08 · Message Brokers — RabbitMQ

> ⭐ **The one idea:** A broker **decouples** producer from consumer — the redirect publishes an event and returns immediately; a separate process consumes it whenever, independently. That decoupling — not "sending a message" — is the entire point.

---

## 1. Why a broker at all

Without one: write analytics inline in the redirect endpoint → the redirect's speed is now coupled to however slow analytics processing is. With one: **publish an event and return immediately**; a **separate consumer** processes it independently, at its own pace. Producers and consumers work independently — this is **event-driven architecture**.

## 2. aio-pika — the async RabbitMQ client

RabbitMQ speaks **AMQP** (`amqp://`). **aio-pika** = the async Python client (vs sync `pika`) — the RabbitMQ equivalent of `asyncpg`/`redis.asyncio`. Async matters: a blocking client would freeze the event loop while waiting on the network.

## 3. Core concepts

```
producer → EXCHANGE (routes) → bound QUEUE(s) → consumer
```

| | What it is |
|---|---|
| **Connection** | the pipe to the broker (`connect_robust` = auto-reconnecting) |
| **Channel** | a lightweight virtual connection *inside* the connection — many per connection |
| **Exchange** | where producers publish; **routes** messages to queues |
| **Queue** | holds messages for consumers; a consumer **binds** a queue to an exchange to receive |

**Exchange types:**
- **fanout** — broadcast to **all** bound queues (used here — pub/sub for click events)
- `direct` — routes by **exact** routing-key match
- `topic` — routes by **pattern** match (`order.*.created`)
- `headers` — routes by message header attributes

**Publish once → every bound queue gets a copy** (fanout).

## 4. Durability

| | Guarantees |
|---|---|
| `durable=True` (exchange/queue) | the **structure** survives a broker restart |
| `DeliveryMode.PERSISTENT` (message) | the **message** is saved to disk, not just memory |

Both are needed together for real durability.

## 5. The producer (publish on redirect)

```python
EXCHANGE_NAME = "clicks"

async def get_channel():
    global _connection, _channel
    if _channel is None or _channel.is_closed:
        _connection = await aio_pika.connect_robust(settings.rabbitmq_url)
        _channel = await _connection.channel()
        await _channel.declare_exchange(EXCHANGE_NAME, aio_pika.ExchangeType.FANOUT, durable=True)
    return _channel

async def publish_click_event(short_code: str) -> None:
    channel = await get_channel()
    exchange = await channel.get_exchange(EXCHANGE_NAME)
    message = aio_pika.Message(
        body=json.dumps({"short_code": short_code}).encode(),
        delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
    )
    await exchange.publish(message, routing_key="")   # fanout ignores routing_key
```

**Lazily created, guarded by `is_closed`** — this pattern happened to make it resilient to loop changes across pytest's per-test event loops (see doc 03 for why Redis, created eagerly at import, did *not* survive the same scenario).

## 6. The consumer — a genuinely separate process

**Web app = request/response** (someone asks, it answers). **Consumer = long-running worker** — no HTTP, no endpoints, nobody connects to it; it just listens forever.

```python
async def process_message(message: aio_pika.abc.AbstractIncomingMessage) -> None:
    async with message.process():              # ack on success, requeue on exception
        data = json.loads(message.body.decode())
        async with async_session() as db:
            db.add(Click(short_code=data["short_code"]))
            await db.commit()

async def main():
    connection = await aio_pika.connect_robust(settings.rabbitmq_url)
    channel = await connection.channel()
    exchange = await channel.declare_exchange(EXCHANGE_NAME, aio_pika.ExchangeType.FANOUT, durable=True)
    queue = await channel.declare_queue("clicks_analytics", durable=True)
    await queue.bind(exchange)                  # ← THIS makes messages actually flow here
    await queue.consume(process_message)
    await asyncio.Future()                      # run forever

if __name__ == "__main__":
    asyncio.run(main())
```

Run: `uv run python -m src.events.consumer` (`-m` treats `src` as a package so imports resolve; `if __name__ == "__main__"` only fires when run directly).

**`async with message.process()` = the ack mechanism.** Success → **ack** (RabbitMQ deletes the message). Exception → message **not acked** → can be **redelivered**. This is at-least-once delivery in code.

**Same image, different `command:`** — in compose, the consumer runs from the identical Docker image as the app, just with a different entrypoint. One image, two roles (web vs worker) — the standard way real deployments run app + background workers.

## 7. Delivery guarantees (the classic interview question)

| Guarantee | Meaning | Trade-off |
|---|---|---|
| **At-most-once** | may lose messages, never duplicates | rare in practice (fire-and-forget) |
| **At-least-once** | never loses, **may duplicate** | the common default — requires acks + redelivery |
| **Exactly-once** | never lost, never duplicated | genuinely hard; most systems don't truly achieve it end-to-end |

**The real-world pattern: at-least-once + idempotent consumers** (processing a duplicate has no ill effect). Saying this in an interview signals real understanding.

**Queue (point-to-point) vs log (pub-sub) — the fundamental model split:**
- **Queue** — each message goes to **exactly one** consumer; multiple consumers = work distributed (load-balanced). Deleted once acked. RabbitMQ's native model. → task queues.
- **Log** — each subscriber gets its **own copy**; messages **retained** in an ordered log, replayable. Kafka's model. → event streaming.

## 8. RabbitMQ vs alternatives

| | RabbitMQ | Kafka | Redis Pub/Sub |
|---|---|---|---|
| Model | queue, smart routing | retained log | fire-and-forget |
| Durability | ✅ | ✅ (replay) | ❌ |
| Best for | task queues, pub/sub, moderate scale | event streaming, huge throughput, replay | lightweight, no durability needed |

## 9. Inspecting RabbitMQ

Management UI at `localhost:15672` — creds from `RABBITMQ_DEFAULT_USER`/`PASS` in compose (default image fallback: `guest`/`guest`, localhost-only). ⚠️ Those env vars only apply on **first container creation** — set later → `docker compose down && up -d` to recreate. Tabs: **Exchanges** (watch publish activity), **Queues** (watch messages arrive/ack), **Connections**.

---

## 🎤 Interview answers

**"Why use a message broker instead of just writing to the DB directly?"**
> Decoupling. If analytics processing lived inline in the redirect handler, any slowness there would directly slow down every redirect. Publishing an event lets the redirect return immediately while a separate consumer processes it independently — the producer and consumer scale, fail, and deploy independently of each other.

**"Explain delivery guarantees."**
> At-most-once can lose messages but never duplicates; at-least-once never loses messages but can deliver duplicates; exactly-once would be both, but it's genuinely hard to achieve end-to-end. Most production systems use at-least-once combined with idempotent consumers — processing the same message twice has no additional effect — rather than chasing true exactly-once.

**"RabbitMQ vs Kafka?"**
> RabbitMQ is a smart broker with rich routing — exchanges direct messages to queues, and once consumed a message is gone. Kafka is a retained, ordered log — every consumer gets its own copy and can replay from any offset, and it's built for very high throughput. RabbitMQ suits task queues and moderate-scale pub/sub; Kafka suits event streaming and audit-style replay at large scale.

---

## ✅ Gotcha checklist

- [ ] Publish is fire-and-continue — the redirect doesn't wait on the consumer
- [ ] `durable` = structure survives restart; `PERSISTENT` = message survives restart — need both
- [ ] A queue must be **bound** to an exchange to receive anything
- [ ] `message.process()` acks on success, leaves unacked (→ redelivery) on exception
- [ ] Fanout ignores routing keys — every bound queue gets every message
- [ ] Consumer = separate long-running process, no HTTP involved
- [ ] Env vars for RabbitMQ creds only apply on first container creation
