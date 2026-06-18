# 1. Escolhe aleatoriamente um Modelo (30x30 ou 60x60)
# 2. Escolhe aleatoriamente um Ganho de Sinal                    
# 3. Sorteia um intervalo de tempo aleatório (ex: 1 a 5 segundos)
# 4. Carrega o arquivo do sinal correspondente (g)            
# 5. Envia esses dados para o Servidor                           
# 6. Espera a resposta (Imagem reconstruída + estatísticas)      
# 7. Salva a imagem e atualiza o relatório final

import socket
import os
import random
import time
import csv

HOST = '127.0.0.1'
PORTA = 8000 #o processo ControlCe já está usando a porta 5000, provavelmente para a funcionalidade AirPlay.

def sortear_cenario():
    """Sorteia um cenário de teste aleatório, lendo o arquivo de sinal correspondente."""
    modelo = random.choice(['30x30', '60x60'])
    ganho = random.randint(1, 2)
    intervalo = random.randint(1, 5)

    if modelo == '30x30':
        caminho_sinal = f'Cgnr/sinais/g-{modelo}-{ganho}.csv'
    else:
        caminho_sinal = f'Cgnr/sinais/G-{ganho}.csv'

    try:
        with open(caminho_sinal, 'r', newline='') as f:
            leitura = csv.reader(f)
            # Converte os dados do csv para uma string única para envio via socket
            sinal_data = "\n".join([";".join(row) for row in leitura])
        
        print(f"[*] Cenário Sorteado: Modelo={modelo}, Ganho={ganho}, Intervalo={intervalo}s")
        print(f"[*] Lendo dados do sinal de: {caminho_sinal}")
        
        return {
            "modelo": modelo,
            "ganho": ganho,
            "intervalo": intervalo,
            "sinal_path": caminho_sinal,
            "sinal_data": sinal_data
        }
    except FileNotFoundError:
        print(f"[!] Erro: Arquivo de sinal não encontrado em '{caminho_sinal}'")
        return None

def conectarServidor():
    """estabelecer uma conexão TCP com o servidor."""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.connect((HOST, PORTA))
        print(f"[*] Conectado com sucesso ao servidor {HOST}:{PORTA}")
        return sock
    except ConnectionRefusedError:
        print("[!] Erro: O servidor não está rodando ou recusou a conexão.")
        return None
    
if __name__ == "__main__":
    print("\n======================================\n")
    print("Iniciando o Cliente de Reconstrução...\n")
    
    cenario = sortear_cenario()
    
    if cenario:
        
        print(f"[*] Aguardando {cenario['intervalo']} segundos antes de conectar...")
        time.sleep(cenario['intervalo'])

        conexao = conectarServidor()
        if conexao:
            payload = f"{cenario['modelo']};{cenario['ganho']}|{cenario['sinal_data']}"
            print(f"[*] Enviando payload de tamanho {len(payload)} caracteres...")
            conexao.sendall(payload.encode('utf-8'))
            conexao.shutdown(socket.SHUT_WR)

            print("[*] Aguardando resposta do servidor...")
            resposta_servidor = conexao.recv(1024).decode('utf-8')
            print(f"[<-] Resposta do Servidor: {resposta_servidor}")
            
            print("\nFinalizando conexão com cliente...")
            print("\n======================================\n")
            conexao.close()