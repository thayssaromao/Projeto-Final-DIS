# Inicialização Global: Carrega os arquivos pesados de matrizes apenas uma vez na memória 
# para que as threads compartilhem esses dados sem redundância.
# Thread Principal (Produtora): Escuta a porta 8000, aceita conexões de rede,
#  lê os dados enviados pelo cliente, empacota-os em uma tarefa e os coloca na fila.
# Threads Trabalhadoras (Consumidoras): Um grupo limitado de até 3 threads pega 
# as tarefas da fila e executa o algoritmo matemático pesado (CGNR/CGNE).
import socket
import numpy as np
import queue
import time
import os
import psutil
from concurrent.futures import ThreadPoolExecutor
import matplotlib.pyplot as plt

from AlgoritmoReconstrucao import (
    carregar_matriz_esparsa,
    executar_algoritmo_aleatorio,
    descobrir_resolucao,
    salvar_imagem
)

HOST = '127.0.0.1'
PORTA = 8000
MAX_TRABALHADORES = 4  # Limite estrito de 4 threads calculando CGNR simultaneamente
LIMITE_CPU = 95.0

historico_requisicoes = []
historico_cpu = []

print("[*] Inicializando o Servidor: Carregando matrizes de modelo esparsas...")

try:
    H_60 = carregar_matriz_esparsa("Cgnr/sinais/H-1.csv") 
    H_30 = carregar_matriz_esparsa("Cgnr/sinais/H-2.csv")
    print("[*] Matrizes de modelo carregadas com sucesso de forma global!")
except Exception as e:
    print(f"[!] Erro crítico ao carregar modelos físicos: {e}")
    exit(1)

def gerar_grafico_desempenho():
    if not historico_requisicoes:
        print("[*] Nenhuma requisição registrada para gerar gráfico.")
        return

    plt.figure(figsize=(10, 5))
    plt.plot(historico_requisicoes, historico_cpu, marker='o', color='b', linestyle='-', linewidth=1.5, label='Uso de CPU (%)')
    
    plt.axhline(y=95.0, color='r', linestyle='--', alpha=0.7, label='Limite de Saturação (95%)')
    
    plt.title('Desempenho da CPU em Função das Requisições Gerais')
    plt.xlabel('ID da Requisição (Ordem de Chegada)')
    plt.ylabel('Uso da CPU (%)')
    plt.ylim(0, 105)
    plt.grid(True, linestyle=':', alpha=0.6)
    plt.legend()
    
    nome_grafico = "desempenho_cpu_requisicoes.png"
    plt.savefig(nome_grafico, dpi=300, bbox_inches='tight')
    print(f"\n[+] Gráfico de desempenho salvo com sucesso como '{nome_grafico}'!")

def aplicar_ganho_sinal(g_dados):
    S, N = g_dados.shape
    print(f"[PROCESSADOR] Aplicando ganho de sinal na matriz de dimensões: {S}x{N}")
    
    # Criamos um array para 'l' variando de 1 até S (1-indexado conforme a fórmula)
    l_indices = np.arange(1, S + 1).reshape(S, 1)  # Reshape para (S, 1) para fazer broadcasting pelas colunas N
    
    # Cálculo do gamma_l para cada linha
    gamma_l = 100 + (1/20) * l_indices * np.sqrt(l_indices)
    
    # Multiplicação elemento a elemento aproveitando o broadcasting do numpy (g_{l,c} * gamma_l)
    g_dados_com_ganho = g_dados * gamma_l
    
    return g_dados_com_ganho

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
    ganho = int(tarefa["ganho"])
    sinal_str = tarefa["sinal_data"]
    
    print(f"\n[TRABALHADOR] Iniciando processamento da Tarefa #{id_tarefa} (Modelo: {modelo}, Ganho: {ganho}(1 ou 0))...")
    
    tempo_inicio = time.time()
    cpu_inicial = psutil.cpu_percent(interval=None)
    mem_inicial = psutil.Process(os.getpid()).memory_info().rss / (1024 * 1024) 
    
    try:
        if "30" in modelo:
            H_atual = H_30
        else:
            H_atual = H_60

        g_linhas = [linha.split(';') for linha in sinal_str.strip().split('\n') if linha]
        g_dados = np.array(g_linhas, dtype=np.float64)

        resultado = executar_algoritmo_aleatorio(H_atual, g_dados)
        
        resolucao = descobrir_resolucao(H_atual)
        nome_imagem = f"imagem_tarefa_{id_tarefa}_{modelo}_{resultado['algoritmo']}.png"
        
        salvar_imagem(resultado["f"], resolucao, nome_imagem)
        print(f"[TRABALHADOR] Imagem salva na raiz: {nome_imagem} usando {resultado['algoritmo']} em {resultado['iteracoes']} iterações.")

    except Exception as error:
        print(f"[!] Erro ao processar o algoritmo na tarefa #{id_tarefa}: {error}")
    
    #Medição final
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
    print(f"[*] Limite da CPU: {LIMITE_CPU} %")
    print("=======================================================\n")

    contador_tarefas = 0

    try:
        while True:
            client_socket, addr = server_socket.accept()

            cpu_atual = psutil.cpu_percent(interval=None)

            contador_tarefas += 1 
            historico_requisicoes.append(contador_tarefas)
            historico_cpu.append(cpu_atual)

            if cpu_atual >= LIMITE_CPU:
                print(f"[!] REQUISIÇÃO RECUSADA: CPU em {cpu_atual}%. Servidor temporariamente indisponível.")
                resposta = "Erro: Servidor sobrecarregado. Tente novamente mais tarde."
                client_socket.sendall(resposta.encode('utf-8'))
                client_socket.close()
                continue

            print(f"\n[*] Conexão de rede vinda de {addr[0]}:{addr[1]}")
            
            dados_recebidos = b""
            while True:
                chunk = client_socket.recv(4096)
                if not chunk:
                    break 
                dados_recebidos += chunk
            
            mensagem = dados_recebidos.decode('utf-8')
            
            if mensagem:
                # contador_tarefas += 1
                
                # Desembrulha o protocolo criado no cliente ("MODELO;GANHO|DADOS_SINAL")
                try:
                    cabecalho, sinal_data = mensagem.split('|', 1)
                    modelo, ganho = cabecalho.split(';')
                except ValueError:
                    modelo, ganho, sinal_data = "Desconhecido", "0", mensagem
                
                tarefa = {
                    "id": contador_tarefas,
                    "modelo": modelo,
                    "ganho": ganho,
                    "sinal_data": sinal_data
                }
                                
                posicao_fila = executor._work_queue.qsize() + 1

                print(f"[+] Tarefa #{contador_tarefas} adicionada à fila. Posição atual: {posicao_fila}")
                
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
    try:
        iniciarServidor()
    except KeyboardInterrupt:
        print("\n[!] Instância do servidor interrompida pelo usuário.")
    finally:
        print("[*] Garantindo salvamento dos dados analíticos...")
        gerar_grafico_desempenho()