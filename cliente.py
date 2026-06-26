import socket
import os
import random
import time
import csv
from concurrent.futures import ThreadPoolExecutor

HOST = '127.0.0.1'
PORTA = 8000
REQUISICOES = 100
PASTA_RESULTADOS = "resultados-relatorio"
RESET = "\033[0m"
COR_CLIENTE = "\033[95m" 
NEGRITO = "\033[1m"


def log_cliente(id_cliente, mensagem):
    print("\n============================================")
    print(f"{COR_CLIENTE}{NEGRITO}[Cliente #{id_cliente}]{RESET} {mensagem}")
    print("============================================")


def garantir_pasta_resultados():
    os.makedirs(PASTA_RESULTADOS, exist_ok=True)


def receber_resposta_completa(conexao):
    partes = []
    while True:
        chunk = conexao.recv(65536)
        if not chunk:
            break
        partes.append(chunk)
    return b"".join(partes).decode("utf-8")


def parse_resposta_servidor(resposta):
    if resposta.startswith("ERRO|"):
        meta = {}
        for par in resposta[5:].split(";"):
            if "=" in par:
                chave, valor = par.split("=", 1)
                meta[chave] = valor
        return {"status": "ERRO", "meta": meta}

    if not resposta.startswith("OK|"):
        return {"status": "DESCONHECIDO", "mensagem": resposta}

    _, stats, _ = resposta.split("|", 2)
    meta = {}
    for par in stats.split(";"):
        if "=" in par:
            chave, valor = par.split("=", 1)
            meta[chave] = valor

    return {"status": "OK", "meta": meta}


def salvar_relatorio_cliente(id_cliente, status, tempo_fim_a_fim, meta=None):
    garantir_pasta_resultados()
    caminho = os.path.join(PASTA_RESULTADOS, "relatorio_cliente.txt")
    existe = os.path.exists(caminho)
    meta = meta or {}

    with open(caminho, "a", encoding="utf-8") as arquivo:
        if not existe:
            arquivo.write(
                "ID_CLIENTE;STATUS;TEMPO_FIM_A_FIM_SEG;ID_TAREFA;ALGORITMO;IMAGEM\n"
            )
        arquivo.write(
            f"{id_cliente};{status};{tempo_fim_a_fim:.4f};"
            f"{meta.get('id_tarefa', '')};{meta.get('algoritmo', '')};"
            f"{meta.get('imagem', '')}\n"
        )


def executar_requisicao_cliente(id_cliente):
    log_cliente(id_cliente, "Iniciando ciclo...")
    cenario = sortear_cenario()

    if not cenario:
        return

    log_cliente(id_cliente, f"Aguardando {cenario['intervalo']}s para simular comportamento real...")
    time.sleep(cenario['intervalo'])

    conexao = conectarServidor()
    if not conexao:
        salvar_relatorio_cliente(id_cliente, "ERRO", 0.0)
        return

    payload = f"{cenario['modelo']};{cenario['ganho']}|{cenario['sinal_data']}"
    tempo_inicio = time.time()

    try:
        conexao.sendall(payload.encode('utf-8'))
        conexao.shutdown(socket.SHUT_WR)

        log_cliente(id_cliente, "Aguardando reconstrução e estatísticas do servidor...")
        resposta_servidor = receber_resposta_completa(conexao)
        tempo_fim_a_fim = time.time() - tempo_inicio
        resultado = parse_resposta_servidor(resposta_servidor)

        if resultado["status"] == "OK":
            meta = resultado["meta"]
            salvar_relatorio_cliente(id_cliente, "OK", tempo_fim_a_fim, meta)

            log_cliente(
                id_cliente,
                f"Tarefa #{meta['id_tarefa']} | {meta['algoritmo']} | "
                f"{meta['iteracoes']} iterações | Tempo servidor: {meta['tempo_total']}s | "
                f"Tempo fim-a-fim: {tempo_fim_a_fim:.2f}s | Erro: {meta['erro']} | "
                f"CPU: {meta['cpu']}% | Mem: {meta['memoria_mb']} MB | "
                f"Imagem (servidor): {PASTA_RESULTADOS}/{meta['imagem']}"
            )
        elif resultado["status"] == "ERRO":
            meta = resultado["meta"]
            salvar_relatorio_cliente(id_cliente, "ERRO", tempo_fim_a_fim, meta)
            log_cliente(id_cliente, f"Erro do servidor: {meta.get('mensagem', resposta_servidor)}")
        else:
            salvar_relatorio_cliente(id_cliente, "DESCONHECIDO", tempo_fim_a_fim)
            log_cliente(id_cliente, f"Resposta inesperada: {resposta_servidor[:200]}")
    except Exception as e:
        tempo_fim_a_fim = time.time() - tempo_inicio
        salvar_relatorio_cliente(id_cliente, "ERRO", tempo_fim_a_fim)
        log_cliente(id_cliente, f"Erro na comunicação: {e}")
    finally:
        conexao.close()


def sortear_cenario():
    """Sorteia um cenário de teste aleatório, lendo o arquivo de sinal correspondente."""
    modelo = random.choice(['30x30', '60x60'])

    ganhoSinal = random.randint(0, 1)
    versao = random.randint(1, 2)
    intervalo = random.randint(1, 5)

    if modelo == '30x30':
        caminho_sinal = f'Cgnr/sinais/g-{modelo}-{versao}.csv'
    else:
        caminho_sinal = f'Cgnr/sinais/G-{versao}.csv'

    try:
        with open(caminho_sinal, 'r', newline='') as f:
            leitura = csv.reader(f)
            sinal_data = "\n".join([";".join(row) for row in leitura])

        print(f"[*] Cenário Sorteado: Modelo={modelo}, Versão={versao}, Ganho de Sinal={ganhoSinal}, Intervalo={intervalo}s")
        print(f"[*] Lendo dados do sinal de: {caminho_sinal}")

        return {
            "modelo": modelo,
            "ganho": ganhoSinal,
            "intervalo": intervalo,
            "sinal_path": caminho_sinal,
            "sinal_data": sinal_data
        }
    except FileNotFoundError:
        print(f"[!] Erro: Arquivo de sinal não encontrado em '{caminho_sinal}'")
        return None


def conectarServidor():
    """Estabelece uma conexão TCP com o servidor."""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.connect((HOST, PORTA))
        print(f"[*] Conectado com sucesso ao servidor {HOST}:{PORTA}")
        return sock
    except ConnectionRefusedError:
        print("[!] Erro: O servidor não está rodando ou recusou a conexão.")
        return None


if __name__ == "__main__":
    print("\n=======================================================")
    print(f"[*] Disparando {REQUISICOES} Requisições Concorrentes...")
    print("=======================================================\n")

    with ThreadPoolExecutor(max_workers=20) as executor:
        executor.map(executar_requisicao_cliente, range(1, REQUISICOES + 1))

    print(f"\n[+] Todas as {REQUISICOES} requisições planejadas foram concluídas!")
