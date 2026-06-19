#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <winsock2.h>
#include <ws2tcpip.h>

#define HOST "127.0.0.1"
#define PORTA 8000
#define TAM_BUFFER 4096

int reconstruir_por_socket(
    const char *modelo,
    const char *ganho,
    const char *sinal_data,
    char *resposta,
    size_t tamanho_resposta
);

int main() {
    WSADATA wsa;
    SOCKET servidor_socket, cliente_socket;
    struct sockaddr_in servidor_addr, cliente_addr;
    int cliente_tamanho = sizeof(cliente_addr);

    printf("[*] Inicializando Winsock...\n");

    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        printf("[!] Erro ao inicializar Winsock. Codigo: %d\n", WSAGetLastError());
        return 1;
    }

    servidor_socket = socket(AF_INET, SOCK_STREAM, 0);

    if (servidor_socket == INVALID_SOCKET) {
        printf("[!] Erro ao criar socket. Codigo: %d\n", WSAGetLastError());
        WSACleanup();
        return 1;
    }

    servidor_addr.sin_family = AF_INET;
    servidor_addr.sin_addr.s_addr = inet_addr(HOST);
    servidor_addr.sin_port = htons(PORTA);

    if (bind(servidor_socket, (struct sockaddr *)&servidor_addr, sizeof(servidor_addr)) == SOCKET_ERROR) {
        printf("[!] Erro no bind. Codigo: %d\n", WSAGetLastError());
        closesocket(servidor_socket);
        WSACleanup();
        return 1;
    }

    if (listen(servidor_socket, 10) == SOCKET_ERROR) {
        printf("[!] Erro no listen. Codigo: %d\n", WSAGetLastError());
        closesocket(servidor_socket);
        WSACleanup();
        return 1;
    }

    printf("\n======================================\n");
    printf("[*] Servidor C ativo em %s:%d\n", HOST, PORTA);
    printf("[*] Aguardando conexoes...\n");
    printf("======================================\n\n");

    while (1) {
        cliente_socket = accept(
            servidor_socket,
            (struct sockaddr *)&cliente_addr,
            &cliente_tamanho
        );

        if (cliente_socket == INVALID_SOCKET) {
            printf("[!] Erro ao aceitar cliente. Codigo: %d\n", WSAGetLastError());
            continue;
        }

        printf("\n[*] Cliente conectado.\n");

        char buffer[TAM_BUFFER];
        char *dados_recebidos = NULL;
        int bytes_recebidos;
        int tamanho_total = 0;

        while ((bytes_recebidos = recv(cliente_socket, buffer, TAM_BUFFER, 0)) > 0) {
            char *novo_buffer = realloc(dados_recebidos, tamanho_total + bytes_recebidos + 1);

            if (novo_buffer == NULL) {
                printf("[!] Erro de memoria ao receber dados.\n");
                free(dados_recebidos);
                closesocket(cliente_socket);
                dados_recebidos = NULL;
                break;
            }

            dados_recebidos = novo_buffer;
            memcpy(dados_recebidos + tamanho_total, buffer, bytes_recebidos);

            tamanho_total += bytes_recebidos;
            dados_recebidos[tamanho_total] = '\0';
        }

        if (dados_recebidos == NULL) {
            continue;
        }

        printf("[*] Mensagem recebida.\n");
        printf("[*] Tamanho recebido: %d caracteres\n", tamanho_total);

        char *separador_payload = strchr(dados_recebidos, '|');

        if (separador_payload == NULL) {
            char resposta[] = "ERRO|formato_invalido;esperado=MODELO;GANHO|SINAL";
            send(cliente_socket, resposta, strlen(resposta), 0);

            free(dados_recebidos);
            closesocket(cliente_socket);
            continue;
        }

        *separador_payload = '\0';

        char *cabecalho = dados_recebidos;
        char *sinal_data = separador_payload + 1;

        char *separador_cabecalho = strchr(cabecalho, ';');

        if (separador_cabecalho == NULL) {
            char resposta[] = "ERRO|formato_invalido;esperado=MODELO;GANHO";
            send(cliente_socket, resposta, strlen(resposta), 0);

            free(dados_recebidos);
            closesocket(cliente_socket);
            continue;
        }

        *separador_cabecalho = '\0';

        char *modelo = cabecalho;
        char *ganho = separador_cabecalho + 1;

        printf("[*] Modelo recebido: %s\n", modelo);
        printf("[*] Ganho recebido: %s\n", ganho);
        printf("[*] Tamanho do sinal recebido: %zu caracteres\n", strlen(sinal_data));

        char resposta[1024];

        reconstruir_por_socket(
            modelo,
            ganho,
            sinal_data,
            resposta,
            sizeof(resposta)
        );

        send(cliente_socket, resposta, strlen(resposta), 0);

        printf("[*] Resposta enviada ao cliente:\n%s\n", resposta);
        printf("[*] Conexao encerrada.\n");

        free(dados_recebidos);
        closesocket(cliente_socket);
    }

    closesocket(servidor_socket);
    WSACleanup();

    return 0;
}