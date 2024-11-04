# Need temporarily set n_set to 2 while doing this test.
li a0, 1024
sd a0, 1024(x0)
li a0, 1016
sd a0, 1016(x0)
li a0, 1008
sd a0, 1008(x0)
li a0, 1000
sd a0, 1000(x0)
li a0, 992
sd a0, 992(x0)
li a0, 984
sd a0, 984(x0)
li a0, 976
sd a0, 976(x0)
li a0, 968
sd a0, 968(x0)
li a0, 960
sd a0, 960(x0)
li a0, 952
sd a0, 952(x0)
li a0, 944
sd a0, 944(x0)
li a0, 936
sd a0, 936(x0)
li a0, 928
sd a0, 928(x0)
li a0, 920
sd a0, 920(x0)
li a0, 912
sd a0, 912(x0)
li a0, 904
sd a0, 904(x0)

# Load the values
ld a0, 1024(x0)  # 3F8
ld a0, 1016(x0)  # 3F0
ld a0, 1008(x0)  # 3E8
ld a0, 1000(x0)  # 3E0
ld a0, 992(x0)   # 3D8
ld a0, 984(x0)   # 3D0
ld a0, 976(x0)   # 3C8
ld a0, 968(x0)   # 3C0
ld a0, 960(x0)   # 3B8
ld a0, 952(x0)   # 3B0
ld a0, 944(x0)   # 3A8
ld a0, 936(x0)   # 3A0
ld a0, 928(x0)   # 398
ld a0, 920(x0)   # 390
ld a0, 912(x0)   # 388
ld a0, 904(x0)   # 380