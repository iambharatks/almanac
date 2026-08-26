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
        vector<int> count(size(s),0);
        count[0] = s[0] == '1';
        if(count[0] == k) return s.substr(0,1);
        pair<int,int> res = {0,size(s)};
        for(r =1 ; r < size(s) ; r++){
            count[r] += count[r-1];
            if(s[r] == '1') count[r] += 1;
            int diff = count[r] - ((l == 0)?0:count[l-1]);
            while(diff >= k){
                if(diff == k && cmp(s,res.first,res.second,l,r)){ 
                    res = {l,r};
                }
                l++;
                diff = count[r]-count[l-1];
                if(diff < k) {
                    l--;
                    break;
                }
            } 
        }

        return (res.second == size(s))?"":s.substr(res.first,res.second-res.first+1);
    }
};