# Papa Caliente (Fabric 1.21.1)

Minijuego de memoria para 2 jugadores. Se entierran Papas Bomba por turnos y luego se excava por turnos sin explotar. Basado en Squid Craft Games 4

**El mod debe estar instalado en el servidor y en todos los clientes** (registra bloques con texturas propias y dibuja la vista previa en el cliente).

## Jugar
- `/papacaliente join` (los 2 jugadores) y luego `/papacaliente start`.
- **Entierro:** primero un jugador entierra **todas** sus *Papas Bomba* (clic derecho sobre la tierra, no sobre piedra ni sobre otra papa) y luego el otro entierra todas las suyas.
- **Excavación:** por turnos, clic izquierdo con la *Pala de Papa Caliente* sobre la tierra.
  - Si sale una bomba, explota y pierde quien cavó.
  - Si crees que ya no quedan bloques seguros, pulsa el botón en tu turno. Si aciertas, ambos pasan a la siguiente ronda. Si fallas, pierdes.
- Hay 8 rondas: 3x3 → 10x10, con 1 a 8 papas por jugador. El tablero empieza en el **centro** del área y en cada ronda crece +1, y al empezar cada ronda una nube densa de polvo de tierra cubre la arena; el tablero se rehace por debajo y, al disiparse la nube, aparece ya expandido. Si superan la 10x10, ganan los dos.
- Juega en **modo Supervivencia** (en Aventura el cliente no permite golpear bloques).
- `/papacaliente salir`, `/papacaliente stop`, `/papacaliente estado`.

## Reglas implementadas
- Muerte súbita: el primer error termina la partida.
- Quién entierra primero se alterna en cada ronda, y empieza a excavar el que no enterró primero.
- Si un jugador se desconecta, gana el otro.
- Siempre quedan al menos 3 bloques de tierra seguros además de todas las papas. En la ronda 1 hay de 0 a 2 piedras.
- Mientras se juega, la arena (10x10, desde una capa debajo hasta dos encima) y el botón están protegidos. Al terminar, se restauran los bloques originales (no se guarda el contenido de cofres u otros bloques con inventario dentro del área).
