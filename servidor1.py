import socket

HOST = '127.0.0.1'
PORTA = 8000

def iniciarServidor():
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)

    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    server_socket.bind((HOST, PORTA))

    server_socket.listen(5)
    print("\n======================================\n")
    print(f"[*] Servidor Python aguardando conexões na porta {PORTA}...")

    try:
        while True:
            client_socket, addr = server_socket.accept()
            print(f"\n[*] Nova conexão recebida de {addr[0]}:{addr[1]}")
            
            dados_recebidos = b""
            
            # Como um sinal g de imagem real pode ser grande, precisamos ler em blocos (chunks)
            while True:
                chunk = client_socket.recv(4096) # Lê blocos de 4KB por vez
                if not chunk:
                    break 
                dados_recebidos += chunk
            
            # Decodifica a string recebida
            mensagem = dados_recebidos.decode('utf-8')
            print(f"[*] Total de bytes recebidos: {len(dados_recebidos)}")
            
            if mensagem:
                print("[*] Processando dados recebidos...")
                
                # Resposta temporária para o cliente saber que deu tudo certo
                resposta = "SUCESSO;Imagem recebida e processada."
                client_socket.sendall(resposta.encode('utf-8'))
            client_socket.close()
            print("[*] Conexão com o cliente encerrada.")

    except KeyboardInterrupt:
        print("\n[!] Desligando o servidor...")
    finally:
        server_socket.close()

if __name__ == "__main__":
    iniciarServidor()