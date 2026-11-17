"""Isolated Android smoke fixture. Accepts disposable credentials; stores data in memory."""

import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer


ROOMS = [
    {"id": 101, "numero": "101", "tipo": "SIMPLE", "precio": 60.00,
     "capacidad": 1, "estado": "LIBRE"},
    {"id": 202, "numero": "202", "tipo": "DOBLE", "precio": 125.50,
     "capacidad": 2, "estado": "OCUPADO"},
]
GUESTS = [
    {"id": 1, "nombre": "Ana", "apellido": "Prueba", "dni": "TEST-0001",
     "procedencia": "Lima", "pais": "Perú", "profesion": "", "telefono": ""},
]
STAYS = [{"id": 1, "huespedId": 1, "habitacionId": 202,
          "fechaSalida": None, "horaSalida": None}]


class Handler(BaseHTTPRequestHandler):
    def respond(self, status, payload=None, cookies=()):
        body = json.dumps(payload).encode() if payload is not None else b""
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for cookie in cookies:
            self.send_header("Set-Cookie", cookie)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/api/usuarios/csrf":
            self.respond(200, {"token": "smoke-token"}, ["XSRF-TOKEN=smoke-token; Path=/"])
        elif self.path.startswith("/api/"):
            if "JSESSIONID=smoke-session" not in self.headers.get("Cookie", ""):
                self.respond(401, {"message": "Inicia sesión"})
            elif self.path == "/api/habitaciones":
                self.respond(200, ROOMS)
            elif self.path == "/api/usuarios/me":
                self.respond(200, {"id": 1, "username": "smoke", "rol": "RECEPCIONISTA"})
            elif self.path == "/api/huespedes":
                self.respond(200, GUESTS)
            elif self.path.startswith("/api/huespedes/dni/"):
                document = self.path.rsplit("/", 1)[-1]
                guest = next((g for g in GUESTS if g["dni"] == document), None)
                self.respond(200, guest) if guest else self.respond(404)
            elif self.path == "/api/registros":
                self.respond(200, STAYS)
            elif self.path.startswith("/api/check-out/habitaciones/"):
                self.respond(200, [])
            else:
                self.respond(404)
        else:
            self.respond(404)

    def do_POST(self):
        if self.path == "/api/usuarios/login":
            data = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
            if self.headers.get("X-XSRF-TOKEN") != "smoke-token":
                self.respond(403)
            elif not data.get("username") or not data.get("password"):
                self.respond(401)
            else:
                self.respond(200, {"id": 1, "username": data["username"], "rol": "RECEPCIONISTA"},
                             ["JSESSIONID=smoke-session; Path=/; HttpOnly"])
        elif self.path == "/api/huespedes":
            if "JSESSIONID=smoke-session" not in self.headers.get("Cookie", ""):
                self.respond(401)
                return
            if self.headers.get("X-XSRF-TOKEN") != "smoke-token":
                self.respond(403)
                return
            data = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
            if any(g["dni"] == data.get("dni") for g in GUESTS):
                self.respond(409, {"message": "Documento duplicado"})
                return
            data["id"] = max(g["id"] for g in GUESTS) + 1
            GUESTS.append(data)
            self.respond(200, data)
        elif self.path == "/api/usuarios/logout":
            self.respond(204, cookies=["JSESSIONID=; Max-Age=0; Path=/"])
        else:
            self.respond(404)

    def do_PUT(self):
        if not self.path.startswith("/api/huespedes/"):
            self.respond(404)
            return
        if "JSESSIONID=smoke-session" not in self.headers.get("Cookie", ""):
            self.respond(401)
            return
        if self.headers.get("X-XSRF-TOKEN") != "smoke-token":
            self.respond(403)
            return
        guest_id = int(self.path.rsplit("/", 1)[-1])
        data = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        if any(g["dni"] == data.get("dni") and g["id"] != guest_id for g in GUESTS):
            self.respond(409, {"message": "Documento duplicado"})
            return
        for guest in GUESTS:
            if guest["id"] == guest_id:
                guest.update(data)
                self.respond(200, guest)
                return
        self.respond(404)


HTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 8081), Handler).serve_forever()
