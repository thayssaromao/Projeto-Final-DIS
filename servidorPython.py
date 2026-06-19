import socket
import numpy as np
import queue
import time
import os
import psutil
from concurrent.futures import ThreadPoolExecutor

from AlgoritmoReconstrucao import (
    carregar_matriz_esparsa,
    aplicar_ganho,
    executar_algoritmo_aleatorio,
    descobrir_resolucao,
    salvar_imagem
)

HOST = '127.0.0.1'
PORTA = 8000
MAX_TRABALHADORES = 3  # Limite estrito de 3 threads calculando CGNR simultaneamente

# Fila thread-safe para armazenar as requisições dos clientes
fila_de_tarefas = queue.Queue()

print("[*] Inicializando o Servidor: Carregando matrizes de modelo esparsas...")
try:
    # Ajuste os caminhos se os arquivos estiverem em outra pasta
    H_60 = carregar_matriz_esparsa("Cgnr/sinais/H-1.csv") # Modelo para 60x60
    H_30 = carregar_matriz_esparsa("Cgnr/sinais/H-2.csv") # Modelo para 30x30
    print("[*] Matrizes de modelo carregadas com sucesso de forma global!")
except Exception as e:
    print(f"[!] Erro crítico ao carregar modelos físicos: {e}")
    exit(1)

def salvar_relatorio(id_tarefa, modelo, ganho, tempo_exec, cpu_uso, mem_uso):
    """Gera e atualiza o relatório comparativo de desempenho exigido pelo professor."""
    caminho_relatorio = "relatorio_servidor_python.txt"
    existe = os.path.exists(caminho_relatorio)
    
    with open(caminho_relatorio, "a") as f:
        if not existe:
            f.write("ID_TAREFA;MODELO;GANHO;TEMPO_EXEC_SEG;CPU_MED_PORCENTAGEM;MEM_MED_MB\n")
        f.write(f"{id_tarefa};{modelo};{ganho};{tempo_exec:.4f};{cpu_uso:.2f};{mem_uso:.2f}\n")
    print(f"[*] Estatísticas da tarefa {id_tarefa} salvas em '{caminho_relatorio}'")

def processar_cgnr_trabalhador(tarefa):
    """Função executada pelas threads trabalhadoras em segundo plano."""
    id_tarefa = tarefa["id"]
    modelo = tarefa["modelo"]
    ganho = tarefa["ganho"]
    sinal_str = tarefa["sinal_data"]
    
    print(f"\n[TRABALHADOR] Iniciando processamento da Tarefa #{id_tarefa} (Modelo: {modelo}, Ganho: {ganho})...")
    
    # 1. Medição inicial de recursos
    tempo_inicio = time.time()
    cpu_inicial = psutil.cpu_percent(interval=None)
    mem_inicial = psutil.Process(os.getpid()).memory_info().rss / (1024 * 1024) 
    
    # --- ALTERAÇÃO: Execução com mapeamento correto das funções importadas ---
    try:
        # Seleciona a matriz H certa com base no modelo enviado pelo cliente
        if "30" in modelo:
            H_atual = H_30
        else:
            H_atual = H_60

        # Converte a string de dados de sinal recebida da rede para array do numpy
        g_linhas = [linha.split(';') for linha in sinal_str.strip().split('\n') if linha]
        g_dados = np.array(g_linhas, dtype=np.float64)

        # Aplica o ganho matemático importado
        g_com_ganho = aplicar_ganho(g_dados)

        # Executa dinamicamente o algoritmo (CGNR ou CGNE)
        resultado = executar_algoritmo_aleatorio(H_atual, g_com_ganho)
        
        # Reconstrói a resolução e salva a imagem gerada na raiz do servidor
        resolucao = descobrir_resolucao(H_atual)
        nome_imagem = f"imagem_tarefa_{id_tarefa}_{modelo}_{resultado['algoritmo']}.png"
        
        salvar_imagem(resultado["f"], resolucao, nome_imagem)
        print(f"[TRABALHADOR] Imagem salva na raiz: {nome_imagem} usando {resultado['algoritmo']} em {resultado['iteracoes']} iterações.")

    except Exception as error:
        print(f"[!] Erro ao processar o algoritmo na tarefa #{id_tarefa}: {error}")
    
    # 2. Medição final de recursos
    tempo_fim = time.time()
    tempo_total = tempo_fim - tempo_inicio
    cpu_final = psutil.cpu_percent(interval=None)
    mem_final = psutil.Process(os.getpid()).memory_info().rss / (1024 * 1024) 
    
    cpu_medio = max(cpu_final - cpu_inicial, 1.0) 
    mem_gasta = max(mem_final - mem_inicial, 0.1)
    
    print(f"[TRABALHADOR] Concluído Tarefa #{id_tarefa} em {tempo_total:.2f}s!")
    
    alg_utilizado = resultado.get("algoritmo", "Erro") if 'resultado' in locals() else "Erro"
    salvar_relatorio(f"{id_tarefa}_{alg_utilizado}", modelo, ganho, tempo_total, cpu_medio, mem_gasta)

def iniciarServidor():
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_socket.bind((HOST, PORTA))
    server_socket.listen(10) # Aumentado o backlog para suportar mais conexões pendentes
    
    # Inicializa o pool com exatamente 3 threads para consumir a fila
    executor = ThreadPoolExecutor(max_workers=MAX_TRABALHADORES)
    
    print("\n=======================================================")
    print(f"[*] Servidor Python Multi-Thread Ativo na porta {PORTA}")
    print(f"[*] Controle de Saturação: Limite de {MAX_TRABALHADORES} tarefas simultâneas.")
    print("=======================================================\n")

    contador_tarefas = 0

    try:
        while True:
            client_socket, addr = server_socket.accept()
            print(f"\n[*] Conexão de rede vinda de {addr[0]}:{addr[1]}")
            
            dados_recebidos = b""
            while True:
                chunk = client_socket.recv(4096)
                if not chunk:
                    break 
                dados_recebidos += chunk
            
            mensagem = dados_recebidos.decode('utf-8')
            
            if mensagem:
                contador_tarefas += 1
                
                # Desembrulha o protocolo criado no cliente ("MODELO;GANHO|DADOS_SINAL")
                try:
                    cabecalho, sinal_data = mensagem.split('|', 1)
                    modelo, ganho = cabecalho.split(';')
                except ValueError:
                    modelo, ganho, sinal_data = "Desconhecido", "0", mensagem
                
                # Monta o dicionário da tarefa para colocar na fila
                tarefa = {
                    "id": contador_tarefas,
                    "modelo": modelo,
                    "ganho": ganho,
                    "sinal_data": sinal_data
                }
                
                # Coloca a tarefa na fila de execução
                fila_de_tarefas.put(tarefa)
                
                # Descobre a posição atual do cliente na fila
                posicao_fila = fila_de_tarefas.qsize()
                
                print(f"[+] Tarefa #{contador_tarefas} adicionada à fila. Posição atual: {posicao_fila}")
                
                # RESPONDE IMEDIATAMENTE AO CLIENTE CONFORME SOLICITADO
                resposta = f"Recebido! Você está na posição {posicao_fila} da fila."
                client_socket.sendall(resposta.encode('utf-8'))
                
                # Despacha o gerenciamento da tarefa para o Pool de Threads Trabalhadoras
                executor.submit(processar_cgnr_trabalhador, tarefa)
                
            client_socket.close()

    except KeyboardInterrupt:
        print("\n[!] Desligando o servidor de forma segura...")
    finally:
        executor.shutdown(wait=False)
        server_socket.close()

if __name__ == "__main__":
    iniciarServidor()