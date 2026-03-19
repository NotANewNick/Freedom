######################################
from parse_aschex_rsp import parse_aschex_report_rsp
from parse_aschex_evt import parse_aschex_report_evt
from parse_aschex_inf import parse_aschex_report_inf

from parse_aschex_crd import parse_aschex_report_crd
from parse_aschex_acc import parse_aschex_report_acc

from parse_aschex_util import len_byte, len_header, set_parser_para
from parse_aschex_exception import *

parse_aschex_report_bsp = parse_aschex_report_rsp
parse_aschex_report_bvt = parse_aschex_report_evt
parse_aschex_report_bnf = parse_aschex_report_inf
parse_aschex_report_brd = parse_aschex_report_crd
parse_aschex_report_bcc = parse_aschex_report_acc


import sys


def _get_aschex_parser_by_header(aschex_report):
    return globals()['parse_aschex_report_' + _get_header(aschex_report)]

def _get_header(aschex_report):
    len_header = 2
    len_byte = 2
    header = ''.join([chr(int(aschex_report[i : i+len_byte], 16))
                     for i in range(0, len_header * len_byte, len_byte)])
    return header[1:].lower()

def _get_aschex_parser(aschex_report):
    aschex_parser = None
    try:
        aschex_parser = _get_aschex_parser_by_header(aschex_report)
    except:
        print('Get HEX Parser Error: ', sys.exc_info()[0])
    return aschex_parser

def parse_aschex_report(aschex_report):
    gpmsg_parsed = []
    try:
        while aschex_report:
            aschex_parser = _get_aschex_parser(aschex_report)
            gpmsg, len_parsed = aschex_parser(aschex_report)
            aschex_report = aschex_report[len_parsed:]
            gpmsg_parsed.append(gpmsg)
    except:
        print('Parse HEX Error: ', sys.exc_info()[0])
    return gpmsg_parsed

def set_parameter(dbg_on):
    set_parser_para(dbg_on)

def show_asc_report(aschex, aschex_report_tm, msg_parsed):
    prefix = '[%s]' % aschex_report_tm
    print('%sHEX:\t%s' % (prefix, aschex))
    for msg in msg_parsed:
        print('%sCRC:\t%s' % (prefix, msg['checksum']))
        print('%sASC:\t%s' % (prefix, ','.join(msg['asc_report'])))

def parse_rt(string):
   final = parse_aschex_report_rsp(string)
   return final


x = parse_rt('2B5253500700FE0FBF005D2F06050A060C22384E5A0B0B010001002100100100000000000000000000000000000000000000000000000460000056782D8000000000000000000000000000000000000007E10318070723005352160D0A')
print(x)


