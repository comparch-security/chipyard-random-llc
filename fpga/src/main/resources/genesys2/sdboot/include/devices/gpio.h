// See LICENSE.Sifive for license details.

#ifndef _SIFIVE_GPIO_H
#define _SIFIVE_GPIO_H

#define GPIO_INPUT_VAL  (0x00)
#define GPIO_INPUT_EN   (0x04)
#define GPIO_OUTPUT_EN  (0x08)
#define GPIO_OUTPUT_VAL (0x0C)
#define GPIO_PULLUP_EN  (0x10)
#define GPIO_DRIVE      (0x14)
#define GPIO_RISE_IE    (0x18)
#define GPIO_RISE_IP    (0x1C)
#define GPIO_FALL_IE    (0x20)
#define GPIO_FALL_IP    (0x24)
#define GPIO_HIGH_IE    (0x28)
#define GPIO_HIGH_IP    (0x2C)
#define GPIO_LOW_IE     (0x30)
#define GPIO_LOW_IP     (0x34)
#define GPIO_IOF_EN     (0x38)
#define GPIO_IOF_SEL    (0x3C)
#define GPIO_OUTPUT_XOR    (0x40)
#define GPIO_PATH_HIE      (0x44)
#define GPIO_PATH_LIE      (0x48)
#define GPIO_PS            (0x4C)
#define GPIO_POE           (0x50)
#define GPIO_IOD_SW        (0x54)
#define GPIO_IOD_BUT       (0x58)
#define GPIO_IOD_LED       (0x5C)
#define GPIO_IOD_OLED      (0x60)

#endif /* _SIFIVE_GPIO_H */
