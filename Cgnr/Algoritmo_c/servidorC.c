#define _WIN32_WINNT 0x0600

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>

#define HOST "127.0.0.1"
#define PORTA 8000
#define TAM_BUFFER 4096

#define MAX_TRABALHADORES 3
#define LIMITE_CPU 95.0
#define LIMITE_MEMORIA 95.0

/*
    Se der problema estranho com várias threads ao mesmo tempo,
    mude para 1.

    0 = permite até 3 reconstruções simultâneas.
    1 = protege o algoritmo com mutex, mas executa uma reconstrução por vez.
*/
#define PROTEGER_ALGORITMO_COM_MUTEX 0

typedef struct Tarefa {
    int id;
    char modelo[32];
    char ganho[32];
    char *sinal_data;
    struct Tarefa *proxima;
} Tarefa;

Tarefa *inicio_fila = NULL;
Tarefa *fim_fila = NULL;
int tamanho_fila = 0;

CRITICAL_SECTION mutex_fila;
CRITICAL_SECTION mutex_relatorio;
CRITICAL_SECTION mutex_recursos;
CRITICAL_SECTION mutex_algoritmo;

CONDITION_VARIABLE condicao_fila;

double cpu_atual = 0.0;
double memoria_atual = 0.0;

int contador_tarefas = 0;

int reconstruir_por_socket(
    const char *modelo,
    const char *ganho,
    const char *sinal_data,
    char *resposta,
    size_t tamanho_resposta
);

char *duplicar_string(const char *texto) {
    char *copia = malloc(strlen(texto) + 1);

    if (copia == NULL) {
        return NULL;
    }

    strcpy(copia, texto);
    return copia;
}

double tempo_atual_segundos(void) {
    static LARGE_INTEGER frequencia;
    static int inicializado = 0;

    LARGE_INTEGER contador;

    if (!inicializado) {
        QueryPerformanceFrequency(&frequencia);
        inicializado = 1;
    }

    QueryPerformanceCounter(&contador);

    return (double)contador.QuadPart / (double)frequencia.QuadPart;
}

ULONGLONG filetime_para_ull(FILETIME ft) {
    ULARGE_INTEGER valor;
    valor.LowPart = ft.dwLowDateTime;
    valor.HighPart = ft.dwHighDateTime;
    return valor.QuadPart;
}

double medir_cpu_percentual(void) {
    static ULONGLONG idle_anterior = 0;
    static ULONGLONG kernel_anterior = 0;
    static ULONGLONG user_anterior = 0;

    FILETIME idle_time;
    FILETIME kernel_time;
    FILETIME user_time;

    if (!GetSystemTimes(&idle_time, &kernel_time, &user_time)) {
        return 0.0;
    }

    ULONGLONG idle = filetime_para_ull(idle_time);
    ULONGLONG kernel = filetime_para_ull(kernel_time);
    ULONGLONG user = filetime_para_ull(user_time);

    if (idle_anterior == 0 && kernel_anterior == 0 && user_anterior == 0) {
        idle_anterior = idle;
        kernel_anterior = kernel;
        user_anterior = user;
        return 0.0;
    }

    ULONGLONG idle_diff = idle - idle_anterior;
    ULONGLONG kernel_diff = kernel - kernel_anterior;
    ULONGLONG user_diff = user - user_anterior;

    ULONGLONG total = kernel_diff + user_diff;

    idle_anterior = idle;
    kernel_anterior = kernel;
    user_anterior = user;

    if (total == 0) {
        return 0.0;
    }

    double cpu = (1.0 - ((double)idle_diff / (double)total)) * 100.0;

    if (cpu < 0.0) cpu = 0.0;
    if (cpu > 100.0) cpu = 100.0;

    return cpu;
}

double medir_memoria_percentual(void) {
    MEMORYSTATUSEX status;
    status.dwLength = sizeof(status);

    if (!GlobalMemoryStatusEx(&status)) {
        return 0.0;
    }

    return (double)status.dwMemoryLoad;
}

DWORD WINAPI monitorar_recursos(LPVOID arg) {
    (void)arg;

    while (1) {
        double cpu = medir_cpu_percentual();
        double memoria = medir_memoria_percentual();

        EnterCriticalSection(&mutex_recursos);
        cpu_atual = cpu;
        memoria_atual = memoria;
        LeaveCriticalSection(&mutex_recursos);

        Sleep(1000);
    }

    return 0;
}

void obter_recursos(double *cpu, double *memoria) {
    EnterCriticalSection(&mutex_recursos);

    *cpu = cpu_atual;
    *memoria = memoria_atual;

    LeaveCriticalSection(&mutex_recursos);
}

int servidor_saturado(double *cpu, double *memoria) {
    obter_recursos(cpu, memoria);

    return (*cpu >= LIMITE_CPU || *memoria >= LIMITE_MEMORIA);
}

int adicionar_tarefa_na_fila(Tarefa *tarefa) {
    EnterCriticalSection(&mutex_fila);

    tarefa->proxima = NULL;

    if (fim_fila == NULL) {
        inicio_fila = tarefa;
        fim_fila = tarefa;
    } else {
        fim_fila->proxima = tarefa;
        fim_fila = tarefa;
    }

    tamanho_fila++;

    int posicao = tamanho_fila;

    WakeConditionVariable(&condicao_fila);

    LeaveCriticalSection(&mutex_fila);

    return posicao;
}

Tarefa *pegar_tarefa_da_fila(void) {
    EnterCriticalSection(&mutex_fila);

    while (inicio_fila == NULL) {
        SleepConditionVariableCS(&condicao_fila, &mutex_fila, INFINITE);
    }

    Tarefa *tarefa = inicio_fila;
    inicio_fila = inicio_fila->proxima;

    if (inicio_fila == NULL) {
        fim_fila = NULL;
    }

    tamanho_fila--;

    LeaveCriticalSection(&mutex_fila);

    return tarefa;
}

void salvar_relatorio(
    int id_tarefa,
    const char *modelo,
    const char *ganho,
    double tempo_total,
    double cpu,
    double memoria,
    const char *resposta_algoritmo
) {
    EnterCriticalSection(&mutex_relatorio);

    FILE *teste = fopen("relatorio_servidor_c.txt", "r");
    int existe = teste != NULL;

    if (teste != NULL) {
        fclose(teste);
    }

    FILE *arquivo = fopen("relatorio_servidor_c.txt", "a");

    if (arquivo == NULL) {
        printf("[!] Erro ao abrir relatorio_servidor_c.txt\n");
        LeaveCriticalSection(&mutex_relatorio);
        return;
    }

    if (!existe) {
        fprintf(
            arquivo,
            "ID_TAREFA;MODELO;GANHO;TEMPO_TOTAL_SEG;CPU_PORCENTAGEM;MEMORIA_PORCENTAGEM;RESPOSTA_ALGORITMO\n"
        );
    }

    fprintf(
        arquivo,
        "%d;%s;%s;%.4f;%.2f;%.2f;\"%s\"\n",
        id_tarefa,
        modelo,
        ganho,
        tempo_total,
        cpu,
        memoria,
        resposta_algoritmo
    );

    fclose(arquivo);

    LeaveCriticalSection(&mutex_relatorio);
}

DWORD WINAPI trabalhador(LPVOID arg) {
    int id_trabalhador = (int)(intptr_t)arg;

    while (1) {
        Tarefa *tarefa = pegar_tarefa_da_fila();

        printf(
            "\n[TRABALHADOR %d] Iniciando tarefa #%d | Modelo=%s | Ganho=%s\n",
            id_trabalhador,
            tarefa->id,
            tarefa->modelo,
            tarefa->ganho
        );

        double inicio = tempo_atual_segundos();

        char resposta_algoritmo[4096];

#if PROTEGER_ALGORITMO_COM_MUTEX == 1
        EnterCriticalSection(&mutex_algoritmo);
#endif

        reconstruir_por_socket(
            tarefa->modelo,
            tarefa->ganho,
            tarefa->sinal_data,
            resposta_algoritmo,
            sizeof(resposta_algoritmo)
        );

#if PROTEGER_ALGORITMO_COM_MUTEX == 1
        LeaveCriticalSection(&mutex_algoritmo);
#endif

        double fim = tempo_atual_segundos();
        double tempo_total = fim - inicio;

        double cpu;
        double memoria;
        obter_recursos(&cpu, &memoria);

        printf(
            "[TRABALHADOR %d] Tarefa #%d finalizada em %.4f segundos\n",
            id_trabalhador,
            tarefa->id,
            tempo_total
        );

        printf("[TRABALHADOR %d] Resultado: %s\n", id_trabalhador, resposta_algoritmo);

        salvar_relatorio(
            tarefa->id,
            tarefa->modelo,
            tarefa->ganho,
            tempo_total,
            cpu,
            memoria,
            resposta_algoritmo
        );

        free(tarefa->sinal_data);
        free(tarefa);
    }

    return 0;
}

char *receber_payload(SOCKET cliente_socket, int *tamanho_total) {
    char buffer[TAM_BUFFER];
    char *dados_recebidos = NULL;

    int total = 0;
    int bytes_recebidos;

    while ((bytes_recebidos = recv(cliente_socket, buffer, TAM_BUFFER, 0)) > 0) {
        char *novo_buffer = realloc(dados_recebidos, total + bytes_recebidos + 1);

        if (novo_buffer == NULL) {
            free(dados_recebidos);
            return NULL;
        }

        dados_recebidos = novo_buffer;

        memcpy(dados_recebidos + total, buffer, bytes_recebidos);

        total += bytes_recebidos;
        dados_recebidos[total] = '\0';
    }

    *tamanho_total = total;

    return dados_recebidos;
}

void responder_cliente(SOCKET cliente_socket, const char *resposta) {
    send(cliente_socket, resposta, strlen(resposta), 0);
}

void criar_threads_trabalhadoras(void) {
    for (int i = 0; i < MAX_TRABALHADORES; i++) {
        HANDLE thread = CreateThread(
            NULL,
            0,
            trabalhador,
            (LPVOID)(intptr_t)(i + 1),
            0,
            NULL
        );

        if (thread == NULL) {
            printf("[!] Erro ao criar trabalhador %d\n", i + 1);
            exit(1);
        }

        CloseHandle(thread);
    }
}

int main() {
    WSADATA wsa;
    SOCKET servidor_socket;
    SOCKET cliente_socket;

    struct sockaddr_in servidor_addr;
    struct sockaddr_in cliente_addr;

    InitializeCriticalSection(&mutex_fila);
    InitializeCriticalSection(&mutex_relatorio);
    InitializeCriticalSection(&mutex_recursos);
    InitializeCriticalSection(&mutex_algoritmo);

    InitializeConditionVariable(&condicao_fila);

    CreateThread(NULL, 0, monitorar_recursos, NULL, 0, NULL);

    criar_threads_trabalhadoras();

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

    printf("\n=======================================================\n");
    printf("[*] Servidor C ativo em %s:%d\n", HOST, PORTA);
    printf("[*] Trabalhadores ativos: %d\n", MAX_TRABALHADORES);
    printf("[*] Limite CPU: %.1f%% | Limite memoria: %.1f%%\n", LIMITE_CPU, LIMITE_MEMORIA);
    printf("[*] Aguardando conexoes...\n");
    printf("=======================================================\n\n");

    while (1) {
        int cliente_tamanho = sizeof(cliente_addr);

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

        int tamanho_total = 0;
        char *dados_recebidos = receber_payload(cliente_socket, &tamanho_total);

        if (dados_recebidos == NULL) {
            responder_cliente(cliente_socket, "ERRO|falha_ao_receber_payload");
            closesocket(cliente_socket);
            continue;
        }

        printf("[*] Mensagem recebida.\n");
        printf("[*] Tamanho recebido: %d caracteres\n", tamanho_total);

        char *separador_payload = strchr(dados_recebidos, '|');

        if (separador_payload == NULL) {
            responder_cliente(cliente_socket, "ERRO|formato_invalido;esperado=MODELO;GANHO|SINAL");

            free(dados_recebidos);
            closesocket(cliente_socket);
            continue;
        }

        *separador_payload = '\0';

        char *cabecalho = dados_recebidos;
        char *sinal_data = separador_payload + 1;

        char *separador_cabecalho = strchr(cabecalho, ';');

        if (separador_cabecalho == NULL) {
            responder_cliente(cliente_socket, "ERRO|formato_invalido;esperado=MODELO;GANHO");

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

        double cpu;
        double memoria;

        if (servidor_saturado(&cpu, &memoria)) {
            char resposta[256];

            snprintf(
                resposta,
                sizeof(resposta),
                "RECUSADO|servidor_saturado;cpu=%.2f;memoria=%.2f",
                cpu,
                memoria
            );

            responder_cliente(cliente_socket, resposta);

            printf("[!] Requisicao recusada. CPU=%.2f%% | Memoria=%.2f%%\n", cpu, memoria);

            free(dados_recebidos);
            closesocket(cliente_socket);
            continue;
        }

        Tarefa *tarefa = malloc(sizeof(Tarefa));

        if (tarefa == NULL) {
            responder_cliente(cliente_socket, "ERRO|falha_ao_criar_tarefa");

            free(dados_recebidos);
            closesocket(cliente_socket);
            continue;
        }

        contador_tarefas++;

        tarefa->id = contador_tarefas;

        strncpy(tarefa->modelo, modelo, sizeof(tarefa->modelo) - 1);
        tarefa->modelo[sizeof(tarefa->modelo) - 1] = '\0';

        strncpy(tarefa->ganho, ganho, sizeof(tarefa->ganho) - 1);
        tarefa->ganho[sizeof(tarefa->ganho) - 1] = '\0';

        tarefa->sinal_data = duplicar_string(sinal_data);

        if (tarefa->sinal_data == NULL) {
            responder_cliente(cliente_socket, "ERRO|falha_ao_copiar_sinal");

            free(tarefa);
            free(dados_recebidos);
            closesocket(cliente_socket);
            continue;
        }

        int posicao = adicionar_tarefa_na_fila(tarefa);

        char resposta_cliente[256];

        snprintf(
            resposta_cliente,
            sizeof(resposta_cliente),
            "Recebido! Tarefa #%d adicionada na fila. Posicao atual: %d",
            tarefa->id,
            posicao
        );

        responder_cliente(cliente_socket, resposta_cliente);

        printf("[+] Tarefa #%d adicionada na fila. Posicao atual: %d\n", tarefa->id, posicao);

        free(dados_recebidos);
        closesocket(cliente_socket);

        printf("[*] Conexao encerrada.\n");
    }

    closesocket(servidor_socket);
    WSACleanup();

    return 0;
}