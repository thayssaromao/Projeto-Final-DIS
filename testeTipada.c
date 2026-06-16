#include <stdlib.h>
#include <stdio.h>

void lerArquivo_csv(const char *caminho, double *dados, int linhas, int colunas){
    FILE *arquivo = fopen(caminho, "r");

    if (arquivo == NULL) {
        printf("erro ao abrir arquivo: %s\n", caminho);
        return;
    }

    for(int i = 0; i < linhas; i ++){
        for(int j = 0; j < colunas; j++) {
            if (fscanf(arquivo, "%lf;", &dados[i * colunas + j]) == 0) {
                    break;
                }
        }
    };
    fclose(arquivo);
};
void multiplicaMatrizes(double *res, double *m1, double *m2, int linhas1, int colunas1, int colunas2) {
    for (int i = 0; i < linhas1; i++) {
        for (int j = 0; j < colunas2; j++) {
            res[i * colunas2 + j] = 0.0; 
            for (int k = 0; k < colunas1; k++) {
                res[i * colunas2 + j] += m1[i * colunas1 + k] * m2[k * colunas2 + j];
            }
        }
    }
};

// 1. Representar Matrizes e Vetores na Memória
double *M;
double *N;
double *a; 
double *MN;
double *aM;
double *Ma;

int main() {
    M = (double *)malloc(10 * 10 * sizeof(double));
    N = (double *)malloc(10 * 10 * sizeof(double));
    a = (double *)malloc(10 * sizeof(double));

    MN = (double *)malloc(10 * 10 * sizeof(double));
    aM = (double *)malloc(10 * sizeof(double));
    Ma = (double *)malloc(10 * sizeof(double));

    // Lendo os Arquivos .csv
    lerArquivo_csv("dados/M.csv", M, 10, 10);
    lerArquivo_csv("dados/N.csv", N, 10, 10);
    lerArquivo_csv("dados/a.csv", a, 1, 10); // 'a' lido como vetor linha 1x10

    // Operações
    multiplicaMatrizes(MN, M, N, 10, 10, 10);  // 10x10
    multiplicaMatrizes(aM, a, M, 1, 10, 10);   // 1x10
    multiplicaMatrizes(Ma, M, a, 10, 10, 1);   // 10x1 (aqui 'a' é tratado como vetor coluna)

    printf("Matriz MN (10x10):\n");
    for (int i = 0; i < 10; i++) {
        for (int j = 0; j < 10; j++) {
            printf("%.0f ", MN[i * 10 + j]);
        }
        printf("\n");
    }

    printf("\nVetor aM (1x10):\n");
    for (int j = 0; j < 10; j++) {
        printf("%.2f ", aM[j]);
    }
    printf("\n");

    printf("\nVetor Ma (10x1):\n");
    for (int i = 0; i < 10; i++) {
        printf("%.2f\n", Ma[i]);
    }

    // Liberar memória
    free(M); 
    free(N); 
    free(a);
    free(MN); 
    free(aM); 
    free(Ma);

    return 0;
    }