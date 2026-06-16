import numpy as np

M = np.loadtxt('dados/M.csv', delimiter=';')
N = np.loadtxt('dados/N.csv', delimiter=';')
a = np.loadtxt('dados/a.csv', delimiter=';')

print("Formato de M= ", M.shape)
print("Formato de N= ", N.shape)
print("Formato de a= ", a.shape)

#(produto matricial)
MN = M @ N 
aM = a @ M
Ma = M @ a

print("produto matricial de M @ N = \n", MN)
print("produto matricial de a @ M = \n", aM)
print("produto matricial de M @ a = \n", Ma)


