import subprocess
import sys
import time
import psutil


def memoria_processos_mb(processos):
    total = 0

    for p in processos:
        if p.poll() is not None:
            continue

        try:
            proc = psutil.Process(p.pid)
            total += proc.memory_info().rss / (1024 * 1024)
        except psutil.NoSuchProcess:
            pass

    return total


def medir_saturacao(quantidade_processos):
    print(f"\n=== Teste com {quantidade_processos} requisições simultâneas ===")

    processos = []

    inicio = time.perf_counter()

    cpu_sistema_amostras = []
    memoria_sistema_amostras = []
    memoria_processos_amostras = []

    for _ in range(quantidade_processos):
        p = subprocess.Popen(
            [sys.executable, "CGNR.py"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL
        )
        processos.append(p)

    while any(p.poll() is None for p in processos):
        cpu_sistema_amostras.append(psutil.cpu_percent(interval=0.5))
        memoria_sistema_amostras.append(psutil.virtual_memory().percent)
        memoria_processos_amostras.append(memoria_processos_mb(processos))

    fim = time.perf_counter()

    sucessos = sum(1 for p in processos if p.returncode == 0)
    falhas = quantidade_processos - sucessos

    tempo_total = fim - inicio

    print("Sucessos:", sucessos)
    print("Falhas:", falhas)
    print("Tempo total:", round(tempo_total, 2), "s")
    print("CPU média sistema:", round(sum(cpu_sistema_amostras) / len(cpu_sistema_amostras), 2), "%")
    print("CPU máxima sistema:", round(max(cpu_sistema_amostras), 2), "%")
    print("Memória média sistema:", round(sum(memoria_sistema_amostras) / len(memoria_sistema_amostras), 2), "%")
    print("Memória máxima sistema:", round(max(memoria_sistema_amostras), 2), "%")
    print("Memória máxima processos:", round(max(memoria_processos_amostras), 2), "MB")

    return {
        "requisicoes": quantidade_processos,
        "sucessos": sucessos,
        "falhas": falhas,
        "tempo_total": tempo_total,
        "cpu_media_sistema": sum(cpu_sistema_amostras) / len(cpu_sistema_amostras),
        "cpu_maxima_sistema": max(cpu_sistema_amostras),
        "memoria_media_sistema": sum(memoria_sistema_amostras) / len(memoria_sistema_amostras),
        "memoria_maxima_sistema": max(memoria_sistema_amostras),
        "memoria_maxima_processos_mb": max(memoria_processos_amostras)
    }


if __name__ == "__main__":
    cargas = [1, 2, 3, 4]

    for carga in cargas:
        resultado = medir_saturacao(carga)

        if resultado["falhas"] > 0:
            print("Parando teste: ocorreram falhas.")
            break

        if resultado["memoria_maxima_sistema"] > 95:
            print("Atenção: memória do sistema passou de 95%.")
            break