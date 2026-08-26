class Solution {
    bool cmp(string &s, int l1, int r1, int l2, int r2){
        if(r1-l1 < r2-l2) return false;
        if(r1-l1 > r2-l2) return true;
        while(l1 < r1){
            if(s[l1] < s[l2]) return false;
            if(s[l1] > s[l2]) return true; 
            l1++;
            l2++;
        }
        return false;
    }
public:
    string shortestBeautifulSubstring(string s, int k) {
        int r = 0, l = 0;
        int cur = s[0] == '1';
        if(cur == k) return s.substr(0,1);
        pair<int,int> res = {0,size(s)};
        for(r =1 ; r < size(s) ; r++){
            if(s[r] == '1') cur += 1;
            while(cur >= k){
                if(cur == k && cmp(s,res.first,res.second,l,r)){ 
                    res = {l,r};
                }
                if(s[l] == '1'){
                    cur--;
                }
                l++;
                if(cur < k) {
                    l--;
                    cur++;
                    break;
                }
            } 
        }

        return (res.second == size(s))?"":s.substr(res.first,res.second-res.first+1);
    }
};