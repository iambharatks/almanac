# 06 · Auth & Security

> ⭐ **The one idea:** **Hashing** (one-way, for passwords) ≠ **encoding** (reversible, for JWT payloads) ≠ **encryption** (reversible with a key). A JWT is *signed*, not secret — anyone can read its contents; only the server can prove it hasn't been tampered with.

---

## 1. Password hashing — bcrypt

**Never store plaintext or use a reversible cipher for passwords.** Hash with a **slow, salted** algorithm designed for this: **bcrypt**.

```python
import bcrypt

hashed = bcrypt.hashpw(password.encode()[:72], bcrypt.gensalt())
bcrypt.checkpw(password.encode()[:72], hashed)   # True/False
```

- **Salted** — a random value mixed in per-password so identical passwords hash differently, defeating rainbow tables.
- **Slow by design** (tunable work factor) — makes brute-forcing expensive even if the hash leaks.
- ⚠️ **72-byte limit** — bcrypt silently ignores input beyond 72 bytes. Truncate explicitly.
- **`passlib` dropped** — unmaintained, broke with bcrypt ≥4.1 (`ValueError: password cannot be longer than 72 bytes`). Use `bcrypt` directly.

*Java: `BCryptPasswordEncoder` (Spring Security) — same algorithm, same salting.*

## 2. JWT — structure and the critical distinction

**JWT = JSON Web Token** — `header.payload.signature`, base64url-encoded, dot-separated.

```
eyJhbGc...  .  eyJzdWIi...  .  SflKxwRJ...
  header         payload         signature
```

> ⚠️ **The payload is only ENCODED, not encrypted.** Anyone can base64-decode and read it — never put secrets (passwords, raw keys) in a JWT payload. The **signature** is what's cryptographic: it proves the token wasn't tampered with, using a **secret key only the server has**. Client can *read* the payload; only the server can *forge or verify* a valid signature.

```python
import jwt
token = jwt.encode({"sub": str(user.id), "exp": expiry}, settings.jwt_secret, algorithm="HS256")
payload = jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])   # raises if invalid/expired
```

**Stateless auth:** the server verifies the signature and trusts the payload — no DB lookup, no server-side session store. Scales horizontally for free (any worker/instance can verify any token with the shared secret).

*Java: same JWT standard; `io.jsonwebtoken` (jjwt) or Spring Security's JWT support.*

## 3. Secret key requirements

```
InsecureKeyLengthWarning: The HMAC key is 25 bytes long, below the 
minimum recommended 32 bytes for SHA256 (RFC 7518 Section 3.2).
```

- HMAC-SHA256 produces a **256-bit (32-byte)** digest; a shorter key doesn't provide the algorithm's full security margin.
- **Never hand-type a secret** — even a long human-typed string has weak entropy (dictionary-attackable).
- **Always generate randomly:**
  ```bash
  python -c "import secrets; print(secrets.token_urlsafe(32))"
  ```
- ⚠️ Rotating the secret **invalidates every existing token** — plan for this (forces re-login; production systems version secrets for rotation).

## 4. Auth dependency chain

```python
async def get_current_user(
    token: str = Depends(oauth2_scheme),
    db: AsyncSession = Depends(get_db),
) -> User:
    payload = jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])
    user = await db.get(User, int(payload["sub"]))
    if user is None:
        raise HTTPException(401, "Invalid token")
    return user
```

Used as `user: User = Depends(get_current_user)` on any protected endpoint. **DI chains compose** — `rate_limit` itself does `Depends(get_current_user)`, so `Depends(rate_limit)` gets you auth *and* rate limiting in one line.

**Login form gotcha:** `OAuth2PasswordRequestForm` expects **form-encoded** data (`username`/`password` fields), not JSON — hence Postman needs `x-www-form-urlencoded`, and tests use `data=` not `json=` for the login call. Registration stays plain JSON.

## 5. RBAC & ownership

```python
class User(Base):
    role: Mapped[str] = mapped_column(String, default="user")   # never client-settable

def require_admin(user: User = Depends(get_current_user)):
    if user.role != "admin":
        raise HTTPException(403, "Admin only")
    return user
```

**Ownership check** (not just "logged in", but "is this *your* resource"):
```python
if url.user_id != current_user.id and current_user.role != "admin":
    raise HTTPException(403, "Not your URL")
```

`default="user"` at the DB level, never accepted from the request body, is what prevents privilege escalation at registration.

## 6. Public vs authenticated redirects — a real design decision

Discovered via testing: the redirect endpoint had accidentally picked up an auth dependency, so `GET /{unknown_code}` returned 401 instead of 404.

| | Public redirect (resolved to) | Auth-required redirect |
|---|---|---|
| Model | bit.ly / tinyurl standard | private/internal shortener |
| Writes | authenticated (`POST /urls`) | authenticated |
| Reads | **public** — anyone can follow a shared link | only logged-in users |
| Analytics | captures all clicks | only logged-in clicks |

> 🎤 If asked in an interview why redirects are public: *"Authenticated writes, public reads — the standard split for a link shortener. Only the person creating a link needs an account; anyone should be able to follow it."*

## 7. Secrets — the layered rule

1. **Never in code** — would live in git history forever
2. **Never in the image** — extractable by anyone with the image (`.dockerignore` excludes `.env`)
3. **Injected at runtime** — `.env`/env vars locally, a **secrets manager** in production
4. **Compose files are committed** — must contain `${VAR}` references or `env_file:`, never literal secret values

*(Full production secrets flow — ECS + Secrets Manager — lives in doc 09.)*

---

## 🎤 Interview answers

**"How do you handle password storage?"**
> Bcrypt — a slow, salted, purpose-built hashing algorithm. Salting means identical passwords produce different hashes, defeating rainbow tables, and the deliberate slowness makes brute-forcing expensive even if the hash database leaks. It's one-way — there's no way to recover the plaintext, only to check a candidate against the stored hash.

**"Explain JWT and whether it's secure."**
> A JWT is a signed, not encrypted, token — header, payload, and signature, base64-encoded and dot-separated. Anyone can decode and read the payload, so it must never carry secrets. The security comes from the signature, generated with a server-only secret key, which proves the token hasn't been tampered with. It enables stateless auth: any server instance can verify a token without a shared session store, which is why it scales horizontally so easily.

**"How does your rate limiter stay correct with multiple JWT-authenticated workers?"**
> The JWT payload carries the user ID, which is stable regardless of which worker decodes it, and the rate-limit counter itself lives in Redis, not in-process — so every worker increments the same key for that user. Stateless auth plus externalized counters is what makes it safe under horizontal scaling.

---

## ✅ Gotcha checklist

- [ ] Hash passwords with bcrypt, truncate to 72 bytes
- [ ] JWT payload is readable by anyone — never put secrets in it
- [ ] JWT secret must be ≥32 bytes, randomly generated, never hand-typed
- [ ] Rotating the JWT secret logs everyone out
- [ ] `role` defaults server-side, never accepted from the client
- [ ] Check ownership, not just authentication, for resource access
- [ ] Login uses form data; register uses JSON
- [ ] Decide public vs authenticated reads deliberately — don't let it be accidental
