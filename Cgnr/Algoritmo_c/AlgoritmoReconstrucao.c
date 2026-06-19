#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include <stdint.h>
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"


typedef struct {
    int linhas;
    int colunas;
    int nnz;
    int *row_ptr;
    int *col_idx;
    double *valores;
} MatrizEsparsa;

typedef struct {
    double *f;
    int tamanho_f;
    double tempo;
    int iteracoes;
    double erro;
    double erro_abs;
    double lambda;
    char algoritmo[8];
} Resultado;

static double tempo_atual(void) {
    return (double)clock() / CLOCKS_PER_SEC;
}

static char *criar_nome_cache(const char *nome_arquivo, const char *sufixo) {
    size_t tam = strlen(nome_arquivo) + strlen(sufixo) + 1;
    char *cache = (char *)malloc(tam);
    if (!cache) {
        fprintf(stderr, "Erro de memoria ao criar nome de cache.\n");
        exit(1);
    }
    snprintf(cache, tam, "%s%s", nome_arquivo, sufixo);
    return cache;
}

static int arquivo_existe(const char *nome_arquivo) {
    FILE *fp = fopen(nome_arquivo, "rb");
    if (fp) {
        fclose(fp);
        return 1;
    }
    return 0;
}

static void liberar_matriz(MatrizEsparsa *H) {
    if (!H) return;
    free(H->row_ptr);
    free(H->col_idx);
    free(H->valores);
    free(H);
}

static void salvar_matriz_cache(const char *cache, MatrizEsparsa *H) {
    FILE *fp = fopen(cache, "wb");
    if (!fp) {
        fprintf(stderr, "Nao foi possivel salvar cache da matriz: %s\n", cache);
        return;
    }

    fwrite(&H->linhas, sizeof(int), 1, fp);
    fwrite(&H->colunas, sizeof(int), 1, fp);
    fwrite(&H->nnz, sizeof(int), 1, fp);
    fwrite(H->row_ptr, sizeof(int), H->linhas + 1, fp);
    fwrite(H->col_idx, sizeof(int), H->nnz, fp);
    fwrite(H->valores, sizeof(double), H->nnz, fp);

    fclose(fp);
}

static MatrizEsparsa *carregar_matriz_cache(const char *cache) {
    FILE *fp = fopen(cache, "rb");
    if (!fp) return NULL;

    MatrizEsparsa *H = (MatrizEsparsa *)calloc(1, sizeof(MatrizEsparsa));
    if (!H) {
        fclose(fp);
        return NULL;
    }

    if (fread(&H->linhas, sizeof(int), 1, fp) != 1 ||
        fread(&H->colunas, sizeof(int), 1, fp) != 1 ||
        fread(&H->nnz, sizeof(int), 1, fp) != 1) {
        fclose(fp);
        liberar_matriz(H);
        return NULL;
    }

    H->row_ptr = (int *)malloc(sizeof(int) * (H->linhas + 1));
    H->col_idx = (int *)malloc(sizeof(int) * H->nnz);
    H->valores = (double *)malloc(sizeof(double) * H->nnz);

    if (!H->row_ptr || !H->col_idx || !H->valores) {
        fclose(fp);
        liberar_matriz(H);
        return NULL;
    }

    if (fread(H->row_ptr, sizeof(int), H->linhas + 1, fp) != (size_t)(H->linhas + 1) ||
        fread(H->col_idx, sizeof(int), H->nnz, fp) != (size_t)H->nnz ||
        fread(H->valores, sizeof(double), H->nnz, fp) != (size_t)H->nnz) {
        fclose(fp);
        liberar_matriz(H);
        return NULL;
    }

    fclose(fp);
    return H;
}

MatrizEsparsa *carregar_matriz_esparsa(const char *nome_arquivo) {
    char *cache = criar_nome_cache(nome_arquivo, ".bin");

    if (arquivo_existe(cache)) {
        printf("Carregando matriz esparsa do cache %s...\n", cache);
        MatrizEsparsa *H = carregar_matriz_cache(cache);
        free(cache);
        return H;
    }

    printf("Convertendo CSV %s para matriz esparsa...\n", nome_arquivo);

    FILE *fp = fopen(nome_arquivo, "r");
    if (!fp) {
        fprintf(stderr, "Erro ao abrir matriz H: %s\n", nome_arquivo);
        free(cache);
        exit(1);
    }

    int capacidade = 1024;
    int nnz = 0;
    int linhas = 0;
    int colunas = 0;

    int *linhas_coo = (int *)malloc(sizeof(int) * capacidade);
    int *colunas_coo = (int *)malloc(sizeof(int) * capacidade);
    double *valores_coo = (double *)malloc(sizeof(double) * capacidade);

    if (!linhas_coo || !colunas_coo || !valores_coo) {
        fprintf(stderr, "Erro de memoria ao carregar matriz.\n");
        fclose(fp);
        free(cache);
        exit(1);
    }

    char buffer[1048576];

    while (fgets(buffer, sizeof(buffer), fp)) {
        int coluna_atual = 0;
        char *token = strtok(buffer, ",\n\r");

        while (token != NULL) {
            if (strlen(token) > 0) {
                double valor = atof(token);

                if (valor != 0.0) {
                    if (nnz >= capacidade) {
                        capacidade *= 2;
                        linhas_coo = (int *)realloc(linhas_coo, sizeof(int) * capacidade);
                        colunas_coo = (int *)realloc(colunas_coo, sizeof(int) * capacidade);
                        valores_coo = (double *)realloc(valores_coo, sizeof(double) * capacidade);

                        if (!linhas_coo || !colunas_coo || !valores_coo) {
                            fprintf(stderr, "Erro de memoria ao expandir matriz.\n");
                            fclose(fp);
                            free(cache);
                            exit(1);
                        }
                    }

                    linhas_coo[nnz] = linhas;
                    colunas_coo[nnz] = coluna_atual;
                    valores_coo[nnz] = valor;
                    nnz++;
                }
            }

            coluna_atual++;
            token = strtok(NULL, ",\n\r");
        }

        if (coluna_atual > colunas) {
            colunas = coluna_atual;
        }

        linhas++;
    }

    fclose(fp);

    MatrizEsparsa *H = (MatrizEsparsa *)calloc(1, sizeof(MatrizEsparsa));
    H->linhas = linhas;
    H->colunas = colunas;
    H->nnz = nnz;
    H->row_ptr = (int *)calloc(linhas + 1, sizeof(int));
    H->col_idx = (int *)malloc(sizeof(int) * nnz);
    H->valores = (double *)malloc(sizeof(double) * nnz);

    if (!H || !H->row_ptr || !H->col_idx || !H->valores) {
        fprintf(stderr, "Erro de memoria ao montar CSR.\n");
        free(cache);
        exit(1);
    }

    for (int k = 0; k < nnz; k++) {
        H->row_ptr[linhas_coo[k] + 1]++;
    }

    for (int i = 0; i < linhas; i++) {
        H->row_ptr[i + 1] += H->row_ptr[i];
    }

    int *posicoes = (int *)malloc(sizeof(int) * linhas);
    memcpy(posicoes, H->row_ptr, sizeof(int) * linhas);

    for (int k = 0; k < nnz; k++) {
        int linha = linhas_coo[k];
        int destino = posicoes[linha]++;
        H->col_idx[destino] = colunas_coo[k];
        H->valores[destino] = valores_coo[k];
    }

    free(posicoes);
    free(linhas_coo);
    free(colunas_coo);
    free(valores_coo);

    salvar_matriz_cache(cache, H);
    printf("Matriz esparsa salva em cache: (%d, %d), nnz=%d\n", H->linhas, H->colunas, H->nnz);

    free(cache);
    return H;
}

double *carregar_csv(const char *nome_arquivo, int *tamanho) {
    FILE *fp = fopen(nome_arquivo, "r");
    if (!fp) {
        fprintf(stderr, "Erro ao abrir CSV: %s\n", nome_arquivo);
        exit(1);
    }

    int capacidade = 1024;
    int n = 0;
    double *dados = (double *)malloc(sizeof(double) * capacidade);

    if (!dados) {
        fprintf(stderr, "Erro de memoria ao carregar CSV.\n");
        fclose(fp);
        exit(1);
    }

    char buffer[1048576];

    while (fgets(buffer, sizeof(buffer), fp)) {
        char *token = strtok(buffer, ",;\n\r");

        while (token != NULL) {
            if (strlen(token) > 0) {
                if (n >= capacidade) {
                    capacidade *= 2;
                    dados = (double *)realloc(dados, sizeof(double) * capacidade);
                    if (!dados) {
                        fprintf(stderr, "Erro de memoria ao expandir vetor.\n");
                        fclose(fp);
                        exit(1);
                    }
                }

                dados[n++] = atof(token);
            }

            token = strtok(NULL, ",;\n\r");
        }
    }

    fclose(fp);
    *tamanho = n;
    return dados;
}

static double dot(const double *a, const double *b, int n) {
    double soma = 0.0;
    for (int i = 0; i < n; i++) {
        soma += a[i] * b[i];
    }
    return soma;
}

static double norma2(const double *v, int n) {
    return sqrt(dot(v, v, n));
}

static void matvec(MatrizEsparsa *H, const double *x, double *y) {
    for (int i = 0; i < H->linhas; i++) {
        double soma = 0.0;
        for (int k = H->row_ptr[i]; k < H->row_ptr[i + 1]; k++) {
            soma += H->valores[k] * x[H->col_idx[k]];
        }
        y[i] = soma;
    }
}

static void transposta_matvec(MatrizEsparsa *H, const double *x, double *y) {
    for (int j = 0; j < H->colunas; j++) {
        y[j] = 0.0;
    }

    for (int i = 0; i < H->linhas; i++) {
        for (int k = H->row_ptr[i]; k < H->row_ptr[i + 1]; k++) {
            int j = H->col_idx[k];
            y[j] += H->valores[k] * x[i];
        }
    }
}

double calcular_coeficiente_regularizacao(MatrizEsparsa *H, const double *g) {
    double *Htg = (double *)calloc(H->colunas, sizeof(double));
    if (!Htg) {
        fprintf(stderr, "Erro de memoria ao calcular lambda.\n");
        exit(1);
    }

    transposta_matvec(H, g, Htg);

    double max_abs = 0.0;
    for (int i = 0; i < H->colunas; i++) {
        double valor = fabs(Htg[i]);
        if (valor > max_abs) {
            max_abs = valor;
        }
    }

    free(Htg);
    return max_abs * 0.10;
}

Resultado cgnr(MatrizEsparsa *H, const double *g, double tol, int max_iter) {
    double inicio = tempo_atual();

    if (H->linhas <= 0 || H->colunas <= 0) {
        fprintf(stderr, "Matriz H invalida.\n");
        exit(1);
    }

    Resultado resultado;
    resultado.tamanho_f = H->colunas;
    resultado.f = (double *)calloc(H->colunas, sizeof(double));
    resultado.iteracoes = 0;
    resultado.erro = INFINITY;
    resultado.erro_abs = INFINITY;
    resultado.lambda = calcular_coeficiente_regularizacao(H, g);
    strcpy(resultado.algoritmo, "CGNR");

    double *r = (double *)malloc(sizeof(double) * H->linhas);
    double *z = (double *)calloc(H->colunas, sizeof(double));
    double *p = (double *)calloc(H->colunas, sizeof(double));
    double *w = (double *)calloc(H->linhas, sizeof(double));
    double *z_novo = (double *)calloc(H->colunas, sizeof(double));

    if (!resultado.f || !r || !z || !p || !w || !z_novo) {
        fprintf(stderr, "Erro de memoria no CGNR.\n");
        exit(1);
    }

    memcpy(r, g, sizeof(double) * H->linhas);
    transposta_matvec(H, r, z);
    memcpy(p, z, sizeof(double) * H->colunas);

    double norm_z_sq = dot(z, z, H->colunas);

    for (int i = 0; i < max_iter; i++) {
        resultado.iteracoes = i + 1;

        matvec(H, p, w);
        double norm_w_sq = dot(w, w, H->linhas);

        if (norm_w_sq == 0.0) {
            break;
        }

        double alpha = norm_z_sq / norm_w_sq;

        for (int j = 0; j < H->colunas; j++) {
            resultado.f[j] += alpha * p[j];
        }

        for (int j = 0; j < H->linhas; j++) {
            r[j] -= alpha * w[j];
        }

        resultado.erro = norma2(r, H->linhas);
        resultado.erro_abs = fabs(resultado.erro);

        if (resultado.erro < tol) {
            break;
        }

        transposta_matvec(H, r, z_novo);
        double norm_z_novo_sq = dot(z_novo, z_novo, H->colunas);

        if (norm_z_sq == 0.0) {
            break;
        }

        double beta = norm_z_novo_sq / norm_z_sq;

        for (int j = 0; j < H->colunas; j++) {
            p[j] = z_novo[j] + beta * p[j];
            z[j] = z_novo[j];
        }

        norm_z_sq = norm_z_novo_sq;
    }

    double fim = tempo_atual();
    resultado.tempo = fim - inicio;

    free(r);
    free(z);
    free(p);
    free(w);
    free(z_novo);

    return resultado;
}

Resultado cgne(MatrizEsparsa *H, const double *g, double tol, int max_iter) {
    double inicio = tempo_atual();

    Resultado resultado;
    resultado.tamanho_f = H->colunas;
    resultado.f = (double *)calloc(H->colunas, sizeof(double));
    resultado.iteracoes = 0;
    resultado.erro = INFINITY;
    resultado.erro_abs = INFINITY;
    resultado.lambda = calcular_coeficiente_regularizacao(H, g);
    strcpy(resultado.algoritmo, "CGNE");

    double *r = (double *)malloc(sizeof(double) * H->linhas);
    double *p = (double *)calloc(H->colunas, sizeof(double));
    double *Hp = (double *)calloc(H->linhas, sizeof(double));

    if (!resultado.f || !r || !p || !Hp) {
        fprintf(stderr, "Erro de memoria no CGNE.\n");
        exit(1);
    }

    memcpy(r, g, sizeof(double) * H->linhas);
    transposta_matvec(H, r, p);

    for (int i = 0; i < max_iter; i++) {
        resultado.iteracoes = i + 1;

        double norm_r_sq = dot(r, r, H->linhas);
        double norm_p_sq = dot(p, p, H->colunas);

        if (norm_p_sq == 0.0) {
            break;
        }

        double alpha = norm_r_sq / norm_p_sq;

        for (int j = 0; j < H->colunas; j++) {
            resultado.f[j] += alpha * p[j];
        }

        matvec(H, p, Hp);

        for (int j = 0; j < H->linhas; j++) {
            r[j] -= alpha * Hp[j];
        }

        resultado.erro = norma2(r, H->linhas);
        resultado.erro_abs = fabs(resultado.erro);

        if (resultado.erro < tol) {
            break;
        }

        double norm_r_novo_sq = dot(r, r, H->linhas);

        if (norm_r_sq == 0.0) {
            break;
        }

        double beta = norm_r_novo_sq / norm_r_sq;

        double *Htr = (double *)calloc(H->colunas, sizeof(double));
        if (!Htr) {
            fprintf(stderr, "Erro de memoria no CGNE.\n");
            exit(1);
        }

        transposta_matvec(H, r, Htr);

        for (int j = 0; j < H->colunas; j++) {
            p[j] = Htr[j] + beta * p[j];
        }

        free(Htr);
    }

    double fim = tempo_atual();
    resultado.tempo = fim - inicio;

    free(r);
    free(p);
    free(Hp);

    return resultado;
}

Resultado executar_algoritmo_aleatorio(MatrizEsparsa *H, const double *g) {
    if (rand() % 2 == 0) {
        return cgnr(H, g, 1e-4, 10);
    }

    return cgne(H, g, 1e-4, 10);
}

int descobrir_resolucao(MatrizEsparsa *H) {
    int pixels = H->colunas;
    int resolucao = (int)sqrt((double)pixels);

    if (resolucao * resolucao != pixels) {
        fprintf(stderr, "H tem %d colunas, nao forma imagem quadrada.\n", pixels);
        exit(1);
    }

    return resolucao;
}

void salvar_imagem(const double *f, int resolucao, const char *nome_arquivo) {
    int largura = resolucao;
    int altura = resolucao;
    int total = largura * altura;

    double f_min = f[0];
    double f_max = f[0];

    for (int i = 1; i < total; i++) {
        if (f[i] < f_min) f_min = f[i];
        if (f[i] > f_max) f_max = f[i];
    }

    double f_range = (f_max != f_min) ? (f_max - f_min) : 1.0;

    unsigned char *img = malloc(total * sizeof(unsigned char));

    if (!img) {
        fprintf(stderr, "Erro de memoria ao criar imagem.\n");
        exit(1);
    }

    for (int y = 0; y < altura; y++) {
        for (int x = 0; x < largura; x++) {
            int indice_f = x * altura + y;
            int indice_img = y * largura + x;

            double normalizado = (f[indice_f] - f_min) / f_range * 255.0;

            if (normalizado < 0.0) normalizado = 0.0;
            if (normalizado > 255.0) normalizado = 255.0;

            img[indice_img] = (unsigned char)normalizado;
        }
    }

    int sucesso = stbi_write_png(
        nome_arquivo,
        largura,
        altura,
        1,
        img,
        largura
    );

    free(img);

    if (!sucesso) {
        fprintf(stderr, "Erro ao salvar PNG: %s\n", nome_arquivo);
        exit(1);
    }
}

int main(void) {
    srand((unsigned int)time(NULL));

    int resolucao_desejada = 60;
    const char *arquivo_H;
    const char *arquivo_g;

    if (resolucao_desejada == 60) {
        arquivo_H = "sinais/H-1.csv";
        arquivo_g = "sinais/G-1.csv";
    } else if (resolucao_desejada == 30) {
        arquivo_H = "sinais/H-2.csv";
        arquivo_g = "sinais/G-1.csv";
    } else {
        fprintf(stderr, "Resolucao invalida. Use 30 ou 60.\n");
        return 1;
    }

    printf("Carregando H e g...\n");

    MatrizEsparsa *H = carregar_matriz_esparsa(arquivo_H);

    int tamanho_g = 0;
    double *g = carregar_csv(arquivo_g, &tamanho_g);

    if (H->linhas != tamanho_g) {
        fprintf(stderr, "Dimensoes incompativeis: H tem %d linhas, mas g tem %d elementos.\n",
                H->linhas, tamanho_g);
        liberar_matriz(H);
        free(g);
        return 1;
    }

    int resolucao = descobrir_resolucao(H);

    printf("Resolucao: %dx%d\n", resolucao, resolucao);
    printf("H: (%d, %d), nnz=%d\n", H->linhas, H->colunas, H->nnz);
    printf("g: (%d)\n", tamanho_g);

    double inicio_total = tempo_atual();

    Resultado resultado = executar_algoritmo_aleatorio(H, g);

    double fim_total = tempo_atual();

    char nome_imagem[256];
    snprintf(nome_imagem, sizeof(nome_imagem), "imagem_reconstruida_%dx%d_%s.png",
    resolucao, resolucao, resultado.algoritmo);

    salvar_imagem(resultado.f, resolucao, nome_imagem);

    printf("Algoritmo usado: %s\n", resultado.algoritmo);
    printf("Tempo algoritmo: %.6f\n", resultado.tempo);
    printf("Tempo total: %.6f\n", fim_total - inicio_total);
    printf("Iteracoes: %d\n", resultado.iteracoes);
    printf("Erro: %.12f\n", resultado.erro);
    printf("Lambda: %.12f\n", resultado.lambda);
    printf("Imagem salva em: %s\n", nome_imagem);
    printf("Finalizado.\n");

    free(resultado.f);
    free(g);
    liberar_matriz(H);

    return 0;
}
