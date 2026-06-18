import psutil
import threading
import time
import os

class MonitorRecursos:
    def __init__(self, intervalo=0.2):
        self.intervalo = intervalo
        self.processo = psutil.Process(os.getpid())
        self.ativo = False
        self.thread = None
        self.memoria_max_mb = 0
        self.cpu_processo_amostras = []
        self.cpu_sistema_amostras = []

    def iniciar(self):
        self.ativo = True
        self.inicio = time.perf_counter()

        self.processo.cpu_percent(interval=None)
        psutil.cpu_percent(interval=None)

        self.thread = threading.Thread(target=self._monitorar)
        self.thread.start()

    def _monitorar(self):
        while self.ativo:
            memoria_mb = self.processo.memory_info().rss / (1024 * 1024)
            self.memoria_max_mb = max(self.memoria_max_mb, memoria_mb)

            cpu_processo = self.processo.cpu_percent(interval=None)
            cpu_sistema = psutil.cpu_percent(interval=None)

            self.cpu_processo_amostras.append(cpu_processo)
            self.cpu_sistema_amostras.append(cpu_sistema)

            time.sleep(self.intervalo)

    def parar(self):
        self.ativo = False

        if self.thread is not None:
            self.thread.join()

        fim = time.perf_counter()

        cpu_processo_media = (
            sum(self.cpu_processo_amostras) / len(self.cpu_processo_amostras)
            if self.cpu_processo_amostras else 0
        )

        cpu_sistema_media = (
            sum(self.cpu_sistema_amostras) / len(self.cpu_sistema_amostras)
            if self.cpu_sistema_amostras else 0
        )

        return {
            "tempo_total": fim - self.inicio,
            "memoria_max_mb": self.memoria_max_mb,
            "cpu_processo_media": cpu_processo_media,
            "cpu_sistema_media": cpu_sistema_media
        }