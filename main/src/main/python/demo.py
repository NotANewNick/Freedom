################################################################
# Copyright (C) 2017 Queclink Wirless Solutions Co., Ltd.
# All Rights Reserved
#
# This code is provided by Queclink to its customers as a 
# sample to demonstrate how to how to parse HEX format 
# @Track protocol messages.
#
# Permission to use, copy and modify this code for the purpose
# of use or test Queclink devices is hereby granted, provided 
# that the above copyright notice, this paragraph and the 
# following paragraph appear in all copies, modifications.
#
# You must not distribute this code to others without express 
# authority from Queclink.
################################################################
import sys

from parse_aschex_rsp import parse_aschex_report_rsp
from parse_aschex_evt import parse_aschex_report_evt
from parse_aschex_inf import parse_aschex_report_inf
from parse_aschex_hbd import parse_aschex_report_hbd
from parse_aschex_ack import parse_aschex_report_ack
from parse_aschex_crd import parse_aschex_report_crd
from parse_aschex_acc import parse_aschex_report_acc

from parse_aschex_util import len_byte, len_header, set_parser_para
from parse_aschex_exception import *

parse_aschex_report_bsp = parse_aschex_report_rsp
parse_aschex_report_bvt = parse_aschex_report_evt
parse_aschex_report_bnf = parse_aschex_report_inf
parse_aschex_report_brd = parse_aschex_report_crd
parse_aschex_report_bcc = parse_aschex_report_acc

class GV55AscHexParser(object):
    def _get_aschex_parser_by_header(self, aschex_report):
        return globals()['parse_aschex_report_' + self._get_header(aschex_report)]

    def _get_header(self, aschex_report):
        header = ''.join([chr(int(aschex_report[i : i+len_byte], 16)) 
                         for i in range(0, len_header * len_byte, len_byte)])
        return header[1:].lower()

    def _get_aschex_parser(self, aschex_report):
        aschex_parser = None
        try:
            aschex_parser = self._get_aschex_parser_by_header(aschex_report)
        except:
            print ('Get HEX Parser Error: ', sys.exc_info()[0])
        return aschex_parser

    def parse_aschex_report(self, aschex_report):
        gpmsg_parsed = []
        try:
            while aschex_report:
                aschex_parser = self._get_aschex_parser(aschex_report)
                gpmsg, len_parsed = aschex_parser(aschex_report)
                aschex_report = aschex_report[len_parsed:]
                gpmsg_parsed.append(gpmsg)
        except:
            print ('Parse HEX Error: ', sys.exc_info()[0])
        return gpmsg_parsed

    def set_parameter(self, dbg_on):
        set_parser_para(dbg_on)

    def show_asc_report(self, aschex, aschex_report_tm, msg_parsed):
        prefix = '[%s]' % aschex_report_tm
        print
        '%sHEX:\t%s' % (prefix, aschex)
        for msg in msg_parsed:
            print
            '%sCRC:\t%s' % (prefix, msg['checksum'])
            print
            '%sASC:\t%s' % (prefix, ','.join(msg['asc_report']))

    def parse_rt(self, string):

        aschex_report = string.rstrip('\r\n')
        aschex_report_tm = 'Time'
        self.show_asc_report(aschex_report, aschex_report_tm, self.parse_aschex_report(aschex_report))


def show_asc_report(aschex, aschex_report_tm, msg_parsed):
    prefix = '[%s]' % aschex_report_tm
    print ('%sHEX:\t%s' % (prefix, aschex))
    for msg in msg_parsed:
        print (('%sCRC:\t%s' % (prefix, msg['checksum'])))
        print(msg['asc_report'], "<--------------------")
        print('%sASC:\t%s' % (prefix, ','.join(msg['asc_report'])))
    # print('Message Header:', msg['asc_report'][0])
    # print('Message type :', msg['asc_report'][1])
    # print('Report mask:', msg['asc_report'][2])
    # print('Length:', msg['asc_report'][3])
    # print('Device Type:', msg['asc_report'][4])
    # print('Protocol version:', msg['asc_report'][5])
    # print('Firmware version:', msg['asc_report'][6])
    # print('IMEI :', msg['asc_report'][7])
    # print('Battery Level:', msg['asc_report'][8])
    # print('External Power Voltage:', msg['asc_report'][9])
    # print('Digital input status:', msg['asc_report'][10])
    # print('Digital output status:', msg['asc_report'][11])
    # print('Motion Status:', msg['asc_report'][12])
    # print('Satellites in view:', msg['asc_report'][13])
    # print('Report ID:', msg['asc_report'][14])
    # print('Number:', msg['asc_report'][15])
    # print('GPS Accuracy:', msg['asc_report'][16])
    # print('Speed :', msg['asc_report'][17])
    # print('Azimuth:', msg['asc_report'][18])
    # print('Altitude :', msg['asc_report'][19])
    # print('Longitude :', msg['asc_report'][20])
    # print('Latitude :', msg['asc_report'][21])
    # print('GPS UTC time:', msg['asc_report'][22])
    # print('MCC :', msg['asc_report'][23])
    # print('MNC :', msg['asc_report'][24])
    # print('LAC :', msg['asc_report'][25])
    # print('Cell ID :', msg['asc_report'][26])
    # print('Current Mileage :', msg['asc_report'][27])
    # print('Total Mileage :', msg['asc_report'][28])
    # print('Current Hour Meter count:', msg['asc_report'][29])
    # print('Total Hour meter count:', msg['asc_report'][30])
    # print('Send Time:', msg['asc_report'][31])
    # print('Count Number:', msg['asc_report'][32])
    # print('Checksum:', msg['asc_report'][33])
    # print('Tail char:', msg['asc_report'][34])

def parse_one_file(fn, parser):
    with open(fn, 'r') as f:
        for line in f:
            #print line
            if len(line) < 8 or line.find(']ASC:\t') >= 0 or line.find(']CRC:\t') >= 0:
                continue
            elif line.find(']HEX:\t') >= 0:
                line_content = line.rstrip('\r\n').split(']HEX:\t')
                aschex_report = line_content[1]
                print(aschex_report,'this is the achex_report field')
                aschex_report_tm = line_content[0][1:]
            else:
                aschex_report = line.rstrip('\r\n')
                print(aschex_report,'this is the achex_report field')
                aschex_report_tm = 'Time'
            show_asc_report(aschex_report, aschex_report_tm, parser.parse_aschex_report(aschex_report))

'''def show_asc_report(self,aschex, aschex_report_tm, msg_parsed):
        prefix = '[%s]' % aschex_report_tm
        print
        '%sHEX:\t%s' % (prefix, aschex)
        for msg in msg_parsed:
            print
            '%sCRC:\t%s' % (prefix, msg['checksum'])
            print
            '%sASC:\t%s' % (prefix, ','.join(msg['asc_report']))'''
if __name__ == '__main__':
    parser = GV55AscHexParser()
    #parser.set_parameter(True)

    parser.parse_rt('2b4256542400fc17bf0063560200022a56332205064b090031cd1100000400000085010000000000cd002e00f426ad038929e307e802010e230f02400001008901995d1600000000000002a90900000000000000000007e802010e231145c508a30d0a')
